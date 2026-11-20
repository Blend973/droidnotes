package com.droidnotes.ui.graph

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.droidnotes.data.db.NoteEntity
import com.droidnotes.data.repo.NotesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

enum class GraphNodeType {
    NOTE,
    GHOST_NOTE,
    TAG,
}

data class GraphNode(
    val id: String,
    val label: String,
    val type: GraphNodeType,
    val noteId: String? = null,
    val isBookmarked: Boolean = false,
    val x: Float,
    val y: Float,
    val degree: Int,
)

data class GraphEdge(
    val fromId: String,
    val toId: String,
)

data class GraphUiState(
    val nodes: List<GraphNode> = emptyList(),
    val edges: List<GraphEdge> = emptyList(),
    val showTags: Boolean = true,
    val searchFilter: String = "",
    val selectedNodeId: String? = null,
    val totalNotes: Int = 0,
    val totalConnections: Int = 0,
)

class GraphViewModel(
    private val repository: NotesRepository,
) : ViewModel() {

    private val showTags = MutableStateFlow(true)
    private val searchFilter = MutableStateFlow("")
    private val selectedNodeId = MutableStateFlow<String?>(null)

    private data class GraphLayoutData(
        val nodes: List<GraphNode> = emptyList(),
        val edges: List<GraphEdge> = emptyList(),
        val showTags: Boolean = true,
        val totalNotes: Int = 0,
        val totalConnections: Int = 0,
    )

    private val layoutFlow = combine(
        repository.observeAllNotes(),
        repository.observeAllLinks(),
        showTags,
    ) { notes, links, includeTags ->
        computeGraphLayout(notes, links, includeTags)
    }.flowOn(Dispatchers.Default)

    val uiState: StateFlow<GraphUiState> = combine(
        layoutFlow,
        searchFilter,
        selectedNodeId,
    ) { layout, filter, selected ->
        GraphUiState(
            nodes = layout.nodes,
            edges = layout.edges,
            showTags = layout.showTags,
            searchFilter = filter,
            selectedNodeId = selected?.takeIf { sel -> layout.nodes.any { it.id == sel } },
            totalNotes = layout.totalNotes,
            totalConnections = layout.totalConnections,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = GraphUiState(),
    )

    fun toggleShowTags() {
        showTags.value = !showTags.value
    }

    fun onFilterChange(query: String) {
        searchFilter.value = query
    }

    fun selectNode(nodeId: String?) {
        selectedNodeId.value = nodeId
    }

    private suspend fun computeGraphLayout(
        notes: List<NoteEntity>,
        links: List<com.droidnotes.data.db.NoteLinkEntity>,
        includeTags: Boolean,
    ): GraphLayoutData {
        if (notes.isEmpty()) {
            return GraphLayoutData(showTags = includeTags)
        }

        val noteByLowerTitle = notes.associateBy { it.title.trim().lowercase() }
        val rawNodes = linkedMapOf<String, MutableNode>()
        val edges = mutableListOf<GraphEdge>()

        notes.forEach { note ->
            rawNodes["note:${note.id}"] = MutableNode(
                id = "note:${note.id}",
                label = note.title,
                type = GraphNodeType.NOTE,
                noteId = note.id,
                isBookmarked = note.isBookmarked,
            )
        }

        // Wikilink edges
        links.forEach { link ->
            val srcKey = "note:${link.sourceNoteId}"
            if (srcKey !in rawNodes) return@forEach
            val targetNote = noteByLowerTitle[link.targetTitle.trim().lowercase()]
            val dstKey = if (targetNote != null) {
                "note:${targetNote.id}"
            } else {
                val ghostKey = "ghost:${link.targetTitle.trim().lowercase()}"
                if (ghostKey !in rawNodes) {
                    rawNodes[ghostKey] = MutableNode(
                        id = ghostKey,
                        label = link.targetTitle.trim(),
                        type = GraphNodeType.GHOST_NOTE,
                    )
                }
                ghostKey
            }
            if (srcKey != dstKey) {
                edges.add(GraphEdge(srcKey, dstKey))
            }
        }

        // Tag nodes & edges
        if (includeTags) {
            notes.forEach { note ->
                val srcKey = "note:${note.id}"
                note.tagList().forEach { tag ->
                    val tagKey = "tag:$tag"
                    if (tagKey !in rawNodes) {
                        rawNodes[tagKey] = MutableNode(
                            id = tagKey,
                            label = "#$tag",
                            type = GraphNodeType.TAG,
                        )
                    }
                    edges.add(GraphEdge(srcKey, tagKey))
                }
            }
        }

        val distinctEdges = edges.distinct()
        val degreeMap = mutableMapOf<String, Int>()
        distinctEdges.forEach { e ->
            degreeMap[e.fromId] = (degreeMap[e.fromId] ?: 0) + 1
            degreeMap[e.toId] = (degreeMap[e.toId] ?: 0) + 1
        }

        // Deterministic initial circle placement (compact logical dp)
        val nodeList = rawNodes.values.toList()
        val n = nodeList.size
        val baseRadius = 82f + sqrt(n.toFloat()) * 26f
        nodeList.forEachIndexed { idx, node ->
            val angle = 2.0 * PI * idx / max(1, n)
            val radius = baseRadius + (idx % 4) * 34f
            node.x = (radius * cos(angle)).toFloat()
            node.y = (radius * sin(angle)).toFloat()
        }

        // Force-directed relaxation on Dispatchers.Default (dp world space)
        val indexById = nodeList.withIndex().associate { it.value.id to it.index }
        val iterations = when {
            n <= 80 -> 110
            n <= 180 -> 60
            else -> 28
        }
        val kRepel = 14500f
        val kAttract = 0.042f
        val idealDist = 94f
        val pairStep = if (n > 200) (n / 100).coerceAtLeast(2) else 1

        val dx = FloatArray(n)
        val dy = FloatArray(n)
        repeat(iterations) { step ->
            currentCoroutineContext().ensureActive()
            dx.fill(0f)
            dy.fill(0f)

            for (i in 0 until n) {
                var j = i + 1
                while (j < n) {
                    val rx = nodeList[i].x - nodeList[j].x
                    val ry = nodeList[i].y - nodeList[j].y
                    val distSq = max(100f, rx * rx + ry * ry)
                    val dist = sqrt(distSq)
                    val force = (kRepel * pairStep) / distSq
                    val fx = (rx / dist) * force
                    val fy = (ry / dist) * force
                    dx[i] += fx
                    dy[i] += fy
                    dx[j] -= fx
                    dy[j] -= fy
                    j += pairStep
                }
            }

            distinctEdges.forEach { edge ->
                val i = indexById[edge.fromId] ?: return@forEach
                val j = indexById[edge.toId] ?: return@forEach
                val rx = nodeList[j].x - nodeList[i].x
                val ry = nodeList[j].y - nodeList[i].y
                val dist = max(1f, sqrt(rx * rx + ry * ry))
                val displacement = dist - idealDist
                val fx = (rx / dist) * displacement * kAttract
                val fy = (ry / dist) * displacement * kAttract
                dx[i] += fx
                dy[i] += fy
                dx[j] -= fx
                dy[j] -= fy
            }

            val damping = 0.72f * (1f - step / (iterations * 1.3f))
            for (i in 0 until n) {
                dx[i] -= nodeList[i].x * 0.006f
                dy[i] -= nodeList[i].y * 0.006f
                val stepMax = 22f
                nodeList[i].x += (dx[i] * damping).coerceIn(-stepMax, stepMax)
                nodeList[i].y += (dy[i] * damping).coerceIn(-stepMax, stepMax)
            }
        }

        // Explicit AABB collision resolution pass covering both node circle and label box
        val halfW = FloatArray(n) { i ->
            val lblLen = nodeList[i].label.take(22).length
            val deg = degreeMap[nodeList[i].id] ?: 0
            val nodeRadius = (4f + deg * 0.8f).coerceAtMost(9.5f)
            max(nodeRadius + 8f, lblLen * 2.75f + 10f)
        }
        val halfH = FloatArray(n) { i ->
            val deg = degreeMap[nodeList[i].id] ?: 0
            val nodeRadius = (4f + deg * 0.8f).coerceAtMost(9.5f)
            nodeRadius + 13f
        }
        val collisionSweeps = if (n <= 180) 64 else 24
        val sweepStep = if (n > 220) (n / 110).coerceAtLeast(2) else 1
        repeat(collisionSweeps) {
            currentCoroutineContext().ensureActive()
            var anyOverlap = false
            for (i in 0 until n) {
                var j = i + 1
                while (j < n) {
                    val reqX = halfW[i] + halfW[j] + 6f
                    val reqY = halfH[i] + halfH[j] + 6f
                    var gapX = nodeList[i].x - nodeList[j].x
                    var gapY = nodeList[i].y - nodeList[j].y
                    val absX = kotlin.math.abs(gapX)
                    val absY = kotlin.math.abs(gapY)
                    if (absX < reqX && absY < reqY) {
                        anyOverlap = true
                        if (absX < 0.1f && absY < 0.1f) {
                            val angle = ((i * 7 + j * 13) % 360) * PI / 180.0
                            gapX = cos(angle).toFloat()
                            gapY = sin(angle).toFloat()
                        }
                        val overlapX = reqX - absX
                        val overlapY = reqY - absY
                        if (overlapX < overlapY) {
                            val push = (overlapX * 0.55f) * if (gapX >= 0f) 1f else -1f
                            nodeList[i].x += push
                            nodeList[j].x -= push
                        } else {
                            val push = (overlapY * 0.55f) * if (gapY >= 0f) 1f else -1f
                            nodeList[i].y += push
                            nodeList[j].y -= push
                        }
                    }
                    j += sweepStep
                }
            }
            if (!anyOverlap) return@repeat
        }

        val finalNodes = nodeList.map {
            GraphNode(
                id = it.id,
                label = it.label,
                type = it.type,
                noteId = it.noteId,
                isBookmarked = it.isBookmarked,
                x = it.x,
                y = it.y,
                degree = degreeMap[it.id] ?: 0,
            )
        }

        return GraphLayoutData(
            nodes = finalNodes,
            edges = distinctEdges,
            showTags = includeTags,
            totalNotes = notes.size,
            totalConnections = distinctEdges.size,
        )
    }

    private data class MutableNode(
        val id: String,
        val label: String,
        val type: GraphNodeType,
        val noteId: String? = null,
        val isBookmarked: Boolean = false,
        var x: Float = 0f,
        var y: Float = 0f,
    )

    class Factory(private val repository: NotesRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return GraphViewModel(repository) as T
        }
    }
}
