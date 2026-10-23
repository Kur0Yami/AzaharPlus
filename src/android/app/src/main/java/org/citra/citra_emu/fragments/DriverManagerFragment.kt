// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

package org.citra.citra_emu.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.transition.MaterialSharedAxis
import java.io.IOException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.citra.citra_emu.R
import org.citra.citra_emu.adapters.DriverAdapter
import org.citra.citra_emu.databinding.FragmentDriverManagerBinding
import org.citra.citra_emu.utils.FileUtil.inputStream
import org.citra.citra_emu.utils.GpuDriverHelper
import org.citra.citra_emu.viewmodel.DriverViewModel
import org.citra.citra_emu.viewmodel.HomeViewModel

import android.view.MenuItem
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.citra.citra_emu.utils.DriverFetcher
class DriverManagerFragment : Fragment() {
    private var _binding: FragmentDriverManagerBinding? = null
    private val binding get() = _binding!!

    private val homeViewModel: HomeViewModel by activityViewModels()
    private val driverViewModel: DriverViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDriverManagerBinding.inflate(inflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        homeViewModel.setNavigationVisibility(visible = false, animated = true)
        homeViewModel.setStatusBarShadeVisibility(visible = false)

        if (!driverViewModel.isInteractionAllowed) {
            DriversLoadingDialogFragment().show(
                childFragmentManager,
                DriversLoadingDialogFragment.TAG
            )
        }

        binding.toolbarDrivers.setNavigationOnClickListener {
            binding.root.findNavController().popBackStack()
        }

        binding.buttonInstall.setOnClickListener {
            getDriver.launch(arrayOf("application/zip"))
        }

        binding.toolbarDrivers.menu.add(0, 9001, 0, "Fetch")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        binding.toolbarDrivers.setOnMenuItemClickListener {
            if (it.itemId == 9001) {
                showFetchDialog()
                true
            } else {
                false
            }
        }

        binding.listDrivers.apply {
            layoutManager = GridLayoutManager(
                requireContext(),
                resources.getInteger(R.integer.game_grid_columns)
            )
            adapter = DriverAdapter(driverViewModel)
        }

        viewLifecycleOwner.lifecycleScope.apply {
            launch {
                driverViewModel.driverList.collectLatest {
                    (binding.listDrivers.adapter as DriverAdapter).submitList(it)
                }
            }
            launch {
                driverViewModel.newDriverInstalled.collect {
                    if (_binding != null && it) {
                        (binding.listDrivers.adapter as DriverAdapter).apply {
                            notifyItemChanged(driverViewModel.previouslySelectedDriver)
                            notifyItemChanged(driverViewModel.selectedDriver)
                            driverViewModel.setNewDriverInstalled(false)
                        }
                    }
                }
            }
        }

        setInsets()
    }

    // Start installing requested driver
    override fun onStop() {
        super.onStop()
        driverViewModel.onCloseDriverManager()
    }

    private fun setInsets() = ViewCompat.setOnApplyWindowInsetsListener(
        binding.root
    ) { _: View, windowInsets: WindowInsetsCompat ->
        val barInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
        val cutoutInsets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())

        val leftInsets = barInsets.left + cutoutInsets.left
        val rightInsets = barInsets.right + cutoutInsets.right

        val mlpAppBar = binding.toolbarDrivers.layoutParams as ViewGroup.MarginLayoutParams
        mlpAppBar.leftMargin = leftInsets
        mlpAppBar.rightMargin = rightInsets
        binding.toolbarDrivers.layoutParams = mlpAppBar

        val mlplistDrivers = binding.listDrivers.layoutParams as ViewGroup.MarginLayoutParams
        mlplistDrivers.leftMargin = leftInsets
        mlplistDrivers.rightMargin = rightInsets
        binding.listDrivers.layoutParams = mlplistDrivers

        val fabSpacing = resources.getDimensionPixelSize(R.dimen.spacing_fab)
        val mlpFab =
            binding.buttonInstall.layoutParams as ViewGroup.MarginLayoutParams
        mlpFab.leftMargin = leftInsets + fabSpacing
        mlpFab.rightMargin = rightInsets + fabSpacing
        mlpFab.bottomMargin = barInsets.bottom + fabSpacing
        binding.buttonInstall.layoutParams = mlpFab

        binding.listDrivers.updatePadding(
            bottom = barInsets.bottom +
                resources.getDimensionPixelSize(R.dimen.spacing_bottom_list_fab)
        )

        windowInsets
    }

    private fun showFetchDialog() {
        Toast.makeText(requireContext(), "Mengambil daftar driver...", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val assets = withContext(Dispatchers.IO) { DriverFetcher.fetchAll() }
            if (_binding == null) return@launch
            if (assets.isEmpty()) {
                Toast.makeText(requireContext(), "Tidak ada driver, cek koneksi", Toast.LENGTH_LONG).show()
                return@launch
            }
            val labels = assets.map { it.name + "  [" + it.repo + "]" }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Fetch driver")
                .setItems(labels) { _, which -> installFetched(assets[which]) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun installFetched(asset: DriverFetcher.DriverAsset) {
        IndeterminateProgressDialogFragment.newInstance(
            requireActivity(),
            R.string.installing_driver,
            false
        ) {
            if (GpuDriverHelper.driverStoragePath.findFile(asset.name) != null) {
                return@newInstance getString(R.string.driver_already_installed)
            }
            val result = try {
                DriverFetcher.downloadToDriverStorage(requireContext(), asset)
            } catch (_: IOException) {
                null
            } ?: return@newInstance getString(R.string.select_gpu_driver_error)
            val driverFile = result.first
            val driverData = result.second
            val driverInList =
                driverViewModel.driverList.value.firstOrNull { it.second == driverData }
            if (driverInList != null) {
                driverFile.delete()
                return@newInstance getString(R.string.driver_already_installed)
            }
            driverViewModel.addDriver(Pair(driverFile.uri, driverData))
            driverViewModel.setNewDriverInstalled(true)
            return@newInstance Any()
        }.show(childFragmentManager, IndeterminateProgressDialogFragment.TAG)
    }

    private val getDriver =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { result ->
            if (result == null) {
                return@registerForActivityResult
            }

            IndeterminateProgressDialogFragment.newInstance(
                requireActivity(),
                R.string.installing_driver,
                false
            ) {
                // Ignore file exceptions when a user selects an invalid zip
                val driverFile: DocumentFile
                try {
                    driverFile = GpuDriverHelper.copyDriverToExternalStorage(result)
                        ?: throw IOException("Driver failed validation!")
                } catch (_: IOException) {
                    return@newInstance getString(R.string.select_gpu_driver_error)
                }

                val driverData = GpuDriverHelper.getMetadataFromZip(driverFile.inputStream())
                val driverInList =
                    driverViewModel.driverList.value.firstOrNull { it.second == driverData }
                if (driverInList != null) {
                    driverFile.delete()
                    return@newInstance getString(R.string.driver_already_installed)
                } else {
                    driverViewModel.addDriver(Pair(driverFile.uri, driverData))
                    driverViewModel.setNewDriverInstalled(true)
                }
                return@newInstance Any()
            }.show(childFragmentManager, IndeterminateProgressDialogFragment.TAG)
        }
}
