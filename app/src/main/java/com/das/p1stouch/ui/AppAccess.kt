package com.das.p1stouch.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.das.p1stouch.App
import com.das.p1stouch.data.PrinterConfigRepository
import com.das.p1stouch.printer.PrinterBackend

/** Small manual-DI helper: screens/ViewModels get the single app-wide
 * [PrinterBackend] through this instead of a Hilt graph -- there's only ever
 * one backend instance, so a full DI framework isn't earning its keep here. */
@Composable
fun localBackend(): PrinterBackend = (LocalContext.current.applicationContext as App).backend

@Composable
fun localConfigRepository(): PrinterConfigRepository =
    (LocalContext.current.applicationContext as App).configRepository

/** Builds a screen ViewModel wired to the app's single [PrinterBackend],
 * e.g. `val vm = backendViewModel(::HomeViewModel)`. */
@Composable
inline fun <reified VM : ViewModel> backendViewModel(crossinline create: (PrinterBackend) -> VM): VM {
    val backend = localBackend()
    return viewModel(factory = viewModelFactory { initializer { create(backend) } })
}

/** Builds a screen ViewModel wired to the app's single [PrinterConfigRepository],
 * for screens that manage config (Settings, FirstRun) rather than live printer state. */
@Composable
inline fun <reified VM : ViewModel> configViewModel(crossinline create: (PrinterConfigRepository) -> VM): VM {
    val repo = localConfigRepository()
    return viewModel(factory = viewModelFactory { initializer { create(repo) } })
}
