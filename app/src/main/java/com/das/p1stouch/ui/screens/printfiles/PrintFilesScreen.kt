package com.das.p1stouch.ui.screens.printfiles

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.das.p1stouch.state.PrintFile
import com.das.p1stouch.ui.backendViewModel

/** Port of ui/screens/print_files.py: FTP file list, search, sort by
 * name/date/size, 3MF thumbnails (disk-cached in RealBackend), placeholder
 * icons, loading spinner, tap-to-confirm-print. */
@Composable
fun PrintFilesScreen() {
    val vm = backendViewModel(::PrintFilesViewModel)
    val files by vm.visibleFiles.collectAsState()
    val thumbnails by vm.thumbnails.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    val hasAnyFiles by vm.hasAnyFiles.collectAsState()
    val searchText by vm.searchText.collectAsState()
    val sortField by vm.sortField.collectAsState()
    val sortDesc by vm.sortDesc.collectAsState()

    var pendingPrint by remember { mutableStateOf<PrintFile?>(null) }

    LaunchedEffect(Unit) { vm.refresh() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = searchText,
                onValueChange = vm::setSearchText,
                placeholder = { Text("Search files…") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            SortFieldDropdown(sortField, onSelect = vm::setSortField)
            IconButton(onClick = vm::toggleSortDirection) {
                Text(if (sortDesc) "↓" else "↑", style = MaterialTheme.typography.titleLarge)
            }
        }

        Box(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                !hasAnyFiles -> Text(
                    "No files found on printer.",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge,
                )
                files.isEmpty() -> Text(
                    "No files match your search.",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge,
                )
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(files, key = { it.path }) { file ->
                        PrintFileRow(file, thumbnails[file.path], onClick = { pendingPrint = file })
                    }
                }
            }
        }
    }

    pendingPrint?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingPrint = null },
            title = { Text("Start Print") },
            text = { Text("Print '${file.name}'?") },
            confirmButton = {
                TextButton(onClick = {
                    vm.startPrint(file.path)
                    pendingPrint = null
                }) { Text("Print") }
            },
            dismissButton = {
                TextButton(onClick = { pendingPrint = null }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortFieldDropdown(current: SortField, onSelect: (SortField) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = current.name.lowercase().replaceFirstChar { it.uppercase() },
            onValueChange = {},
            readOnly = true,
            label = { Text("Sort") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.width(140.dp).menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortField.entries.forEach { field ->
                DropdownMenuItem(
                    text = { Text(field.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    onClick = { onSelect(field); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun PrintFileRow(file: PrintFile, thumbnail: android.graphics.Bitmap?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text("📄", style = MaterialTheme.typography.titleLarge)
            }
        }
        Column {
            Text(file.name, style = MaterialTheme.typography.bodyLarge)
            val sizeKb = file.sizeBytes?.let { it / 1024 }
            if (sizeKb != null) {
                Text("$sizeKb KB", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
