package com.das.p1stouch.ui.screens.printfiles

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.das.p1stouch.printer.PrinterBackend
import com.das.p1stouch.state.PrintFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class SortField { NAME, DATE, SIZE }

/** Port of ui/screens/print_files.py's search/sort/thumbnail-cache logic. */
class PrintFilesViewModel(private val backend: PrinterBackend) : ViewModel() {
    val thumbnails: StateFlow<Map<String, Bitmap>> = backend.thumbnails.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap(),
    )

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _searchText = MutableStateFlow("")
    val searchText: StateFlow<String> = _searchText.asStateFlow()

    private val _sortField = MutableStateFlow(SortField.NAME)
    val sortField: StateFlow<SortField> = _sortField.asStateFlow()

    private val _sortDesc = MutableStateFlow(false)
    val sortDesc: StateFlow<Boolean> = _sortDesc.asStateFlow()

    private val allFiles: StateFlow<List<PrintFile>> = backend.fileList.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList(),
    )

    val visibleFiles: StateFlow<List<PrintFile>> = combine(
        allFiles, _searchText, _sortField, _sortDesc,
    ) { files, search, field, desc ->
        val filtered = if (search.isBlank()) {
            files
        } else {
            files.filter { it.name.contains(search, ignoreCase = true) }
        }
        val comparator = when (field) {
            SortField.NAME -> compareBy<PrintFile> { it.name.lowercase() }
            SortField.DATE -> compareBy { it.modifiedEpochMillis ?: 0L }
            SortField.SIZE -> compareBy { it.sizeBytes ?: 0L }
        }
        val sorted = filtered.sortedWith(comparator)
        if (desc) sorted.reversed() else sorted
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hasAnyFiles: StateFlow<Boolean> = allFiles
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            backend.requestFileList()
            _isLoading.value = false
        }
    }

    fun setSearchText(text: String) {
        _searchText.value = text
    }

    fun setSortField(field: SortField) {
        _sortField.value = field
    }

    fun toggleSortDirection() {
        _sortDesc.value = !_sortDesc.value
    }

    fun startPrint(path: String) {
        viewModelScope.launch { backend.startPrint(path) }
    }
}
