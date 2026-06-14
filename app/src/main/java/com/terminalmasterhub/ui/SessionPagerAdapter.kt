package com.terminalmasterhub.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * Adaptador del ViewPager2 para las 4 sesiones de la app.
 *
 * Orden:
 * 0 - Terminal & Python
 * 1 - ADB/Fastboot Universal
 * 2 - Samsung Odin3
 * 3 - Xiaomi MiTool
 */
class SessionPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    companion object {
        const val TAB_TERMINAL = 0
        const val TAB_FASTBOOT = 1
        const val TAB_SAMSUNG = 2
        const val TAB_XIAOMI = 3
        const val TOTAL_TABS = 4
    }

    private val fragments = mutableListOf<Fragment>()

    fun setFragments(terminal: Fragment, fastboot: Fragment, samsung: Fragment, xiaomi: Fragment) {
        fragments.clear()
        fragments.addAll(listOf(terminal, fastboot, samsung, xiaomi))
        notifyDataSetChanged()
    }

    override fun createFragment(position: Int): Fragment {
        return fragments.getOrElse(position) { Fragment() }
    }

    override fun getItemCount(): Int = TOTAL_TABS
}
