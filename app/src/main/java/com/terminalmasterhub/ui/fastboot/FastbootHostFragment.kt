package com.terminalmasterhub.ui.fastboot

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.terminalmasterhub.R
import com.terminalmasterhub.ui.UsbSubPagerAdapter

/**
 * Fragmento host que contiene las sub-pestañas ADB y Fastboot.
 *
 * [Tab 0] ADB Shell — Consola interactiva para adb shell
 * [Tab 1] Fastboot — Comandos de bootloader y flasheo
 *
 * Este fragmento reemplaza al antiguo FastbootFragment en el ViewPager principal.
 */
class FastbootHostFragment : Fragment() {

    private lateinit var tabLayout: TabLayout
    private lateinit var subPager: ViewPager2
    private lateinit var subPagerAdapter: UsbSubPagerAdapter

    // Fragmentos internos
    private val adbFragment = AdbConsoleFragment()
    private val fastbootFragment = FastbootFragment()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_fastboot_host, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tabLayout = view.findViewById(R.id.usbTabLayout)
        subPager = view.findViewById(R.id.usbSubPager)

        setupSubPager()
        setupTabLayout()
    }

    private fun setupSubPager() {
        subPagerAdapter = UsbSubPagerAdapter(requireActivity())
        subPagerAdapter.setFragments(adbFragment, fastbootFragment)
        subPager.adapter = subPagerAdapter
    }

    private fun setupTabLayout() {
        TabLayoutMediator(tabLayout, subPager) { tab, position ->
            when (position) {
                UsbSubPagerAdapter.TAB_ADB -> {
                    tab.text = "ADB Shell"
                    tab.icon = requireContext().getDrawable(R.drawable.ic_adb)
                }
                UsbSubPagerAdapter.TAB_FASTBOOT -> {
                    tab.text = "Fastboot"
                    tab.icon = requireContext().getDrawable(R.drawable.ic_usb)
                }
            }
        }.attach()
    }

    /**
     * Expone los fragmentos hijos para acceso desde MainActivity.
     */
    fun getAdbFragment(): AdbConsoleFragment = adbFragment
    fun getFastbootFragment(): FastbootFragment = fastbootFragment
}
