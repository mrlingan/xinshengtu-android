package com.lingan.imagestudio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lingan.imagestudio.StudioState
import com.lingan.imagestudio.StudioViewModel
import com.lingan.imagestudio.data.ArtworkCollection

@Composable
fun CollectionManager(state: StudioState, vm: StudioViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<ArtworkCollection?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("管理收藏夹") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it.take(40) }, label = { Text(if (editing == null) "新收藏夹名称" else "修改收藏夹名称") }, singleLine = true, enabled = !state.dataBusy && !state.generating)
            Row {
                TextButton(onClick = { vm.editCollection(if (editing == null) "create" else "rename", editing.orEmpty(), name); name = ""; editing = null }, enabled = name.isNotBlank() && !state.dataBusy && !state.generating) { Text(if (editing == null) "新建" else "保存名称") }
                if (editing != null) TextButton(onClick = { editing = null; name = "" }) { Text("取消重命名") }
            }
            if (state.collectionMessage != null) Text(state.collectionMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(state.collections, key = { it.id }) { group ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(group.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { editing = group.id; name = group.name }, enabled = !state.dataBusy && !state.generating) { Text("重命名") }
                        TextButton(onClick = { deleting = group }, enabled = !state.dataBusy && !state.generating) { Text("删除") }
                    }
                }
            }
            Text("删除收藏夹会保留作品。一个作品可以加入多个收藏夹。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
    deleting?.let { group -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除收藏夹？") }, text = { Text("删除“${group.name}”，其中作品仍会保留在作品库中。") },
        confirmButton = { TextButton(onClick = { vm.editCollection("delete", group.id); deleting = null }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}
