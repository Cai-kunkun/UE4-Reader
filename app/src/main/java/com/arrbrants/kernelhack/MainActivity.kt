package com.arrbrants.kernelhack

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this)
        tv.text = getString(R.string.hello)
        tv.setPadding(48, 96, 48, 0)
        setContentView(tv)
    }
}
