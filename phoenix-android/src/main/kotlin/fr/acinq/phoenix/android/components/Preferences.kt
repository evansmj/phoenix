/*
 * Copyright 2021 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.phoenix.android.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.RadioButton
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.acinq.phoenix.android.R
import fr.acinq.phoenix.android.mutedTypo


internal data class PreferenceItem<T>(val item: T, val title: String, val description: String? = null)

@Composable
internal fun <T> ListPreferenceButton(
    title: String,
    subtitle: String,
    enabled: Boolean,
    selectedItem: T,
    preferences: List<PreferenceItem<T>>,
    onPreferenceSubmit: (PreferenceItem<T>) -> Unit,
) {
    var showPreferenceDialog by remember { mutableStateOf(false) }
    Clickable(onClick = {
        if (enabled) {
            showPreferenceDialog = true
        }
    }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 50.dp, top = 10.dp, bottom = 10.dp, end = 16.dp)
                .alpha(if (enabled) 1f else 0.5f)
        ) {
            Text(text = title)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = subtitle, style = mutedTypo())
        }
    }

    if (showPreferenceDialog) {
        ListPreferenceDialog(
            initialPrefIndex = preferences.map { it.item }.indexOf(selectedItem).takeIf { it >= 0 },
            preferences = preferences,
            onSubmit = {
                showPreferenceDialog = false
                onPreferenceSubmit(it)
            },
            onCancel = { showPreferenceDialog = false }
        )
    }
}

@Composable
private fun <T> ListPreferenceDialog(
    initialPrefIndex: Int?,
    preferences: List<PreferenceItem<T>>,
    onSubmit: (PreferenceItem<T>) -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(
        onDismiss = onCancel,
        isScrollable = false,
        buttons = { Button(onClick = onCancel, text = stringResource(id = R.string.btn_cancel), padding = PaddingValues(16.dp)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            LazyColumn {
                itemsIndexed(preferences) { index, item ->
                    PreferenceDialogItem(
                        item = item,
                        selected = index == initialPrefIndex,
                        onClick = onSubmit
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> PreferenceDialogItem(
    item: PreferenceItem<T>,
    selected: Boolean,
    onClick: (PreferenceItem<T>) -> Unit,
) {
    Clickable(onClick = { onClick(item) }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            RadioButton(selected = selected, onClick = { })
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(text = item.title)
                item.description?.let {
                    Text(text = it, style = mutedTypo())
                }
            }
        }
    }
}