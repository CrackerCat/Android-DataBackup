package com.xayah.databackup.fragment.backup

import android.os.Bundle
import android.view.*
import android.view.ViewGroup.LayoutParams
import android.view.animation.AnimationUtils.loadAnimation
import android.view.animation.LayoutAnimationController
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import com.drakeet.multitype.MultiTypeAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.xayah.databackup.R
import com.xayah.databackup.adapter.AppListAdapter
import com.xayah.databackup.data.AppEntity
import com.xayah.databackup.databinding.FragmentBackupBinding
import com.xayah.databackup.util.Command
import com.xayah.databackup.util.Room
import com.xayah.databackup.util.dp
import kotlinx.coroutines.*


class BackupFragment : Fragment() {
    private var _binding: FragmentBackupBinding? = null

    private val binding get() = _binding!!

    lateinit var room: Room

    lateinit var mAdapter: MultiTypeAdapter

    lateinit var appList: MutableList<AppEntity>

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBackupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.viewModel = ViewModelProvider(this)[BackupViewModel::class.java]

        initialize()
    }

    private fun initialize() {
        val mContext = requireContext()
        room = Room(mContext)
        setHasOptionsMenu(true)

        val linearProgressIndicator = LinearProgressIndicator(mContext).apply {
            layoutParams =
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    .apply {
                        marginStart = 100.dp
                        marginEnd = 100.dp
                    }
            trackCornerRadius = 3.dp
            isIndeterminate = true
        }
        binding.linearLayout.addView(linearProgressIndicator)
        mAdapter = MultiTypeAdapter().apply {
            register(AppListAdapter(room))
            CoroutineScope(Dispatchers.IO).launch {
                appList = Command.getAppList(mContext, room)
                items = appList
            }
        }
        binding.recyclerView.apply {
            adapter = mAdapter
            layoutManager = GridLayoutManager(mContext, 1)
            visibility = View.GONE
            layoutAnimation = LayoutAnimationController(
                loadAnimation(
                    context,
                    androidx.appcompat.R.anim.abc_grow_fade_in_from_bottom
                )
            ).apply {
                order = LayoutAnimationController.ORDER_NORMAL
                delay = 0.3F
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            delay(1000)
            withContext(Dispatchers.Main) {
                linearProgressIndicator.visibility = View.GONE
                if (_binding != null)
                    binding.recyclerView.visibility = View.VISIBLE
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.backup, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.backup_reverse -> {
                val items: Array<String> = resources.getStringArray(R.array.reverse_array)
                var choice = 0
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.choose))
                    .setCancelable(true)
                    .setSingleChoiceItems(
                        items,
                        choice
                    ) { _, which ->
                        choice = which
                    }
                    .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                        when (choice) {
                            0 -> {
                                for ((index, _) in appList.withIndex()) {
                                    appList[index].backupApp = true
                                }
                                CoroutineScope(Dispatchers.IO).launch {
                                    room.selectAllApp()
                                }
                                mAdapter.notifyDataSetChanged()
                            }
                            1 -> {
                                for ((index, _) in appList.withIndex()) {
                                    appList[index].backupData = true
                                }
                                CoroutineScope(Dispatchers.IO).launch {
                                    room.selectAllData()
                                }
                                mAdapter.notifyDataSetChanged()
                            }
                            2 -> {
                                for ((index, _) in appList.withIndex()) {
                                    appList[index].backupApp = !appList[index].backupApp
                                }
                                CoroutineScope(Dispatchers.IO).launch {
                                    room.reverseAllApp()
                                }
                                mAdapter.notifyDataSetChanged()
                            }
                            3 -> {
                                for ((index, _) in appList.withIndex()) {
                                    appList[index].backupData = !appList[index].backupData
                                }
                                CoroutineScope(Dispatchers.IO).launch {
                                    room.reverseAllData()
                                }
                                mAdapter.notifyDataSetChanged()
                            }
                        }
                    }
                    .show()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        room.close()
    }
}