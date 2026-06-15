package com.terminalmasterhub.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * Adaptador para sub-pestañas dentro de la sesión USB.
 *
 * [0] ADB Console (AdbConsoleFragment)
 * [1] Fastboot (FastbootFragment)
 */
class UsbSubPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    companion object {
        const val TAB_ADB = 0
        const val TAB_FASTBOOT = 1
        const val TOTAL_SUB_TABS = 2
    }

    private val fragments = mutableListOf<Fragment>()

    fun setFragments(adbFragment: Fragment, fastbootFragment: Fragment) {
        fragments.clear()
        fragments.addAll(listOf(adbFragment, fastbootFragment))
        notifyDataSetChanged()
    }

    override fun createFragment(position: Int): Fragment {
        return fragments.getOrElse(position) { Fragment() }
    }

    override fun getItemCount(): Int = TOTAL_SUB_TABS
}
