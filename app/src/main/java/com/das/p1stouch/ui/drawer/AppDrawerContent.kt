package com.das.p1stouch.ui.drawer

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.das.p1stouch.ui.navigation.Screen

@Composable
fun AppDrawerContent(currentRoute: String?, onNavigate: (String) -> Unit) {
    ModalDrawerSheet {
        Text(
            "P1S Touch",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleLarge,
        )
        HorizontalDivider()
        Screen.drawerItems.forEach { screen ->
            NavigationDrawerItem(
                icon = { screen.icon?.let { Icon(it, contentDescription = null) } },
                label = { Text(screen.title) },
                selected = currentRoute == screen.route,
                onClick = { onNavigate(screen.route) },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}
