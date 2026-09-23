/*
 * Copyright (C) 2026 The LowhaOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package app.lowha.settings

import android.content.Context
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView

/**
 * Grid adapter for the tracked-apps screen.
 *
 * Callers are expected to have filtered the list to packages whose icons
 * actually resolve; the try/catch here is a backstop, not the filter.
 */
class AppIconAdapter(
    private val context: Context,
    private val packages: List<String>
) : BaseAdapter() {

    private val pm: PackageManager = context.packageManager
    private val inflater = LayoutInflater.from(context)

    override fun getCount(): Int = packages.size

    override fun getItem(position: Int): Any = packages[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: inflater.inflate(R.layout.item_app_icon, parent, false)
        val pkg = packages[position]

        val icon = view.findViewById<ImageView>(R.id.app_icon)
        val label = view.findViewById<TextView>(R.id.app_label)

        try {
            icon.setImageDrawable(pm.getApplicationIcon(pkg))
            label.text = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))
        } catch (e: PackageManager.NameNotFoundException) {
            icon.setImageResource(R.drawable.ic_lowha_shield)
            label.text = pkg
        }
        return view
    }
}
