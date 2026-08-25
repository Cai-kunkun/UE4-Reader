#include "kernel.h"
#include <cmath>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/ptrace.h>
#include <sys/uio.h>
#include <map>
#include <string>


//用于获取一个进程中所有的task
bool GetProcessTask(int pid, std::vector<int> & vOutput) {
	char szTaskPath[256];
	sprintf(szTaskPath, "/proc/%d/task", pid);
	DIR *dir = opendir(szTaskPath);
	if (!dir) return false;

    struct dirent *ptr;
	while ((ptr = readdir(dir)) != NULL) {
		if ((strcmp(ptr->d_name, ".") == 0) || (strcmp(ptr->d_name, "..") == 0)) continue;
		if (ptr->d_type != DT_DIR) continue;
		if (strspn(ptr->d_name, "1234567890") != strlen(ptr->d_name)) continue;
		vOutput.push_back(atoi(ptr->d_name));
	}
	closedir(dir);
	return true;
}

// 通过 /proc/<pid>/maps 列出目标进程加载的所有模块 (去重，仅显示每个模块的最低基址)
static void cmd_modules(pid_t pid) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/maps", pid);
    FILE *fp = fopen(path, "r");
    if (!fp) {
        fprintf(stderr, "cannot open %s: %s\n", path, strerror(errno));
        return;
    }

    std::map<std::string, uintptr_t> modules; // 模块路径 -> 最低基址
    char line[1024];
    while (fgets(line, sizeof(line), fp)) {
        uintptr_t start = 0, end = 0;
        char pathBuf[768] = {0};
        // 格式: start-end perms offset dev inode pathname
        int matched = sscanf(line, "%lx-%lx %*4s %*x %*x:%*x %*d %767[^\n]", &start, &end, pathBuf);
        if (matched < 3) continue;
        if (pathBuf[0] != '/') continue; // 跳过匿名映射、堆栈等

        auto it = modules.find(pathBuf);
        if (it == modules.end() || start < it->second) {
            modules[pathBuf] = start;
        }
    }
    fclose(fp);

    printf("%-16s %s\n", "base", "path");
    for (const auto &kv : modules) {
        printf("%016lx %s\n", kv.second, kv.first.c_str());
    }
    printf("[%zu modules]\n", modules.size());
}

static void print_usage() {
    fprintf(stderr,
        "Usage: KernelHack <package> [command] [args...]\n"
        "Commands:\n"
        "  info                 默认。打印 PID、libUE4.so 基址及基址处指针 (default)\n"
        "  modules              列出目标进程加载的所有模块 (list loaded modules)\n"
        "  module <name>        打印指定模块的 base/bss (module base + bss)\n"
        "  read <hexaddr> <size> 读取指定地址的原始字节，以十六进制打印 (raw memory read)\n"
        "  write <hexaddr> <hexdata> 向指定地址写入原始字节 (raw memory write)\n");
}

int main(int argc, char const *argv[]) {
    if (argc < 2) {
        print_usage();
        return 1;
    }
    const char *target_name = argv[1];
    const char *command = (argc > 2) ? argv[2] : "info";

    c_driver driver;

    //检查驱动是否成功对接
    if (!driver.is_ready()) {
        fprintf(stderr, "driver is not ready\n");
        return 1;
    }

    //检查断点是否初始化，若没有初始化，则初始化
    if (!driver.bp_check_inited())
    {
        driver.bp_init_driver();
    }

    pid_t target_pid = driver.get_name_pid(target_name);
    if (target_pid <= 0) {
        fprintf(stderr, "process not found: %s\n", target_name);
        return 1;
    }
    driver.initialize(target_pid);

    if (strcmp(command, "modules") == 0) {
        printf("pid: %d\n", target_pid);
        cmd_modules(target_pid);
        return 0;
    }

    if (strcmp(command, "module") == 0) {
        if (argc < 4) {
            fprintf(stderr, "usage: KernelHack <package> module <module_name>\n");
            return 1;
        }
        const char *modName = argv[3];
        uintptr_t base = driver.get_module_base(modName);
        uintptr_t bss = driver.get_module_bss(modName);
        printf("pid: %d\n", target_pid);
        printf("module: %s\n", modName);
        printf("base: %lx\n", base);
        printf("bss: %lx\n", bss);
        return (base == 0) ? 1 : 0;
    }

    if (strcmp(command, "read") == 0) {
        if (argc < 5) {
            fprintf(stderr, "usage: KernelHack <package> read <hexaddr> <size>\n");
            return 1;
        }
        uintptr_t addr = strtoull(argv[3], nullptr, 16);
        size_t size = strtoull(argv[4], nullptr, 10);
        if (size == 0 || size > 4096) {
            fprintf(stderr, "size must be between 1 and 4096 bytes\n");
            return 1;
        }
        std::vector<uint8_t> buf(size);
        bool ok = driver.read_v2(addr, buf.data(), size);
        printf("pid: %d\n", target_pid);
        printf("addr: %lx\n", addr);
        printf("size: %zu\n", size);
        printf("ok: %s\n", ok ? "true" : "false");
        printf("data: ");
        for (size_t i = 0; i < size; i++) {
            printf("%02x", buf[i]);
        }
        printf("\n");
        return ok ? 0 : 1;
    }

    if (strcmp(command, "write") == 0) {
        if (argc < 5) {
            fprintf(stderr, "usage: KernelHack <package> write <hexaddr> <hexdata>\n");
            return 1;
        }
        uintptr_t addr = strtoull(argv[3], nullptr, 16);
        const char *hexdata = argv[4];
        size_t hexlen = strlen(hexdata);
        if (hexlen == 0 || (hexlen % 2) != 0 || hexlen > 8192) {
            fprintf(stderr, "hexdata must be non-empty, even-length hex string (max 4096 bytes)\n");
            return 1;
        }
        std::vector<uint8_t> buf(hexlen / 2);
        for (size_t i = 0; i < buf.size(); i++) {
            char byte[3] = {hexdata[i*2], hexdata[i*2+1], 0};
            char *end = nullptr;
            buf[i] = (uint8_t)strtoul(byte, &end, 16);
            if (end != byte + 2) {
                fprintf(stderr, "invalid hex at offset %zu\n", i);
                return 1;
            }
        }
        bool ok = driver.write(addr, buf.data(), buf.size());
        printf("pid: %d\n", target_pid);
        printf("addr: %lx\n", addr);
        printf("wrote: %zu\n", buf.size());
        printf("ok: %s\n", ok ? "true" : "false");
        return ok ? 0 : 1;
    }

    if (strcmp(command, "info") != 0) {
        fprintf(stderr, "unknown command: %s\n", command);
        print_usage();
        return 1;
    }

    // 默认行为: info
    uintptr_t UE4 = driver.get_module_base("libUE4.so");

    printf("pid: %d,UE4: %lx\n",target_pid,UE4);

    uintptr_t ptr = driver.read_v2<uintptr_t>(UE4);
    printf("ptr: %lx\n",ptr);
    return 0;
}
