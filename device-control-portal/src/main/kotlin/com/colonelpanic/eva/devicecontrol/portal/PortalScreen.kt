package com.colonelpanic.eva.devicecontrol.portal

import com.colonelpanic.eva.devicecontrol.proto.Bounds
import com.colonelpanic.eva.devicecontrol.proto.Element
import com.colonelpanic.eva.devicecontrol.proto.Observation
import com.colonelpanic.eva.devicecontrol.proto.Orientation
import com.colonelpanic.eva.devicecontrol.proto.Role
import com.colonelpanic.eva.devicecontrol.proto.Screen
import com.colonelpanic.eva.devicecontrol.proto.UnavailableReason
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** An observation plus the password-field texts that `set_text` read-back needs but the host must not see. */
class MappedScreen(
    val observation: Observation,
    val passwordTexts: Map<Int, String> = emptyMap(),
) {
    override fun toString() = "MappedScreen(${observation.observationId})"
}

/**
 * Portal `state` (filtered, full-property tree) to [Observation], matching the host's
 * `bridge/portal/mapping.py`: pre-order traversal that keeps visible nodes with content or
 * interaction flags, numbered in visit order, with layout wrappers collapsed into their parent.
 */
object PortalScreenMapper {
    /** Set by EVA's own Shizuku capture when it stopped at a node or depth cap; Portal never sends it. */
    const val CAPTURE_CAPPED = "capture_capped"

    private val packageRe = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val activityRe = Regex("^[A-Za-z0-9_.$]{1,255}$")

    private val editClasses = setOf("EditText", "AutoCompleteTextView", "MultiAutoCompleteTextView")
    private val switchClasses = setOf("Switch", "SwitchCompat", "SwitchMaterial", "MaterialSwitch", "ToggleButton")
    private val checkClasses = setOf("CheckBox", "RadioButton", "CheckedTextView", "MaterialCheckBox")
    private val listClasses = setOf("ListView", "GridView", "RecyclerView", "ExpandableListView", "AbsListView")
    private val scrollClasses =
        setOf("ScrollView", "HorizontalScrollView", "NestedScrollView", "ViewPager", "ViewPager2")
    private val buttonClasses =
        setOf("Button", "ImageButton", "MaterialButton", "FloatingActionButton", "Chip", "CompoundButton")
    private val pickerClasses = setOf("Spinner", "AppCompatSpinner")
    private val imageClasses = setOf("ImageView", "AppCompatImageView")
    private val textClasses = setOf("TextView", "AppCompatTextView", "MaterialTextView")
    private val interactiveFlags = listOf("isClickable", "isLongClickable", "isEditable", "isScrollable", "isCheckable")

    fun map(
        state: JsonObject,
        observationId: String,
        capturedAt: String,
        backend: String,
        sequence: Long,
    ): MappedScreen {
        val tree = state["a11y_tree"] as? JsonObject
        val phone = state.obj("phone_state")
        val (width, height) = screenSize(state)
        val elements = ArrayList<Element>()
        val passwords = HashMap<Int, String>()
        if (tree != null) {
            keptNodes(tree).forEach { (node, parent) ->
                val element = element(node, elements.size, parent, elements, width, height)
                if (element.password) passwords[element.index] = visibleText(node)
                elements += element
            }
        }
        val packageName = phone.str("packageName").takeIf { packageRe.matches(it) && it.length <= 255 }
        return MappedScreen(
            Observation(
                observationId = observationId,
                capturedAt = capturedAt,
                backend = backend,
                packageName = packageName,
                activity = activity(phone.str("activityName"), packageName.orEmpty()),
                screen =
                    Screen(width, height, if (width > height) Orientation.LANDSCAPE else Orientation.PORTRAIT),
                keyboardShown = phone.bool("keyboardVisible"),
                contentUnavailable = elements.isEmpty(),
                screenOn = (phone["isInteractive"] as? JsonPrimitive)?.booleanOrNull != false,
                locked = phone.bool("isLocked"),
                unavailableReason =
                    if (elements.isNotEmpty()) {
                        null
                    } else if (tree ==
                        null
                    ) {
                        UnavailableReason.A11Y_UNAVAILABLE
                    } else {
                        UnavailableReason.NO_CONTENT
                    },
                elements = elements,
                sequence = sequence,
                elementsCapped = (state[CAPTURE_CAPPED] as? JsonPrimitive)?.booleanOrNull == true,
            ),
            passwords,
        )
    }

    fun screenSize(state: JsonObject): Pair<Int, Int> {
        val context = state.obj("device_context")
        for (key in listOf("screen_bounds", "display_metrics")) {
            val size = context.obj(key)
            val width = size.int("width") ?: size.int("widthPixels")
            val height = size.int("height") ?: size.int("heightPixels")
            if (width != null && height != null && width > 0 && height > 0) return width to height
        }
        (state["a11y_tree"] as? JsonObject)?.let { root ->
            val b = rawBounds(root)
            if (b.right > b.left && b.bottom > b.top) return b.right to b.bottom
        }
        throw IllegalArgumentException("Portal state carries no screen size")
    }

    fun roleFor(node: JsonObject): Role {
        val simple = node.str("className").substringAfterLast('.')
        return when {
            node.bool("isEditable") || simple in editClasses -> {
                Role.EDIT_TEXT
            }

            simple in pickerClasses -> {
                Role.BUTTON
            }

            simple in switchClasses -> {
                Role.SWITCH
            }

            node.bool("isCheckable") || simple in checkClasses -> {
                Role.CHECKBOX
            }

            simple in listClasses || "collectionInfo" in node -> {
                Role.LIST
            }

            node.bool("isScrollable") || simple in scrollClasses -> {
                Role.SCROLLABLE
            }

            simple in buttonClasses -> {
                Role.BUTTON
            }

            simple in imageClasses || simple in textClasses -> {
                when {
                    node.bool("isClickable") -> Role.BUTTON
                    simple in imageClasses -> Role.IMAGE
                    else -> Role.TEXT
                }
            }

            else -> {
                Role.OTHER
            }
        }
    }

    private fun keptNodes(root: JsonObject): Sequence<Pair<JsonObject, Int?>> =
        sequence {
            var count = 0
            val stack = ArrayDeque<Pair<JsonObject, Int?>>().apply { addLast(root to null) }
            while (stack.isNotEmpty()) {
                val (node, ancestor) = stack.removeLast()
                var parent = ancestor
                if (keep(node)) {
                    yield(node to ancestor)
                    parent = count++
                }
                children(node).asReversed().forEach { stack.addLast(it to parent) }
            }
        }

    private fun keep(node: JsonObject): Boolean {
        if ((node["isVisibleToUser"] as? JsonPrimitive)?.booleanOrNull == false) return false
        if (visibleText(node).isNotEmpty() || node.str("contentDescription").isNotEmpty() || node.str("hint").isNotEmpty()) {
            return true
        }
        return interactiveFlags.any { node.bool(it) }
    }

    private fun element(
        node: JsonObject,
        index: Int,
        parent: Int?,
        elements: List<Element>,
        width: Int,
        height: Int,
    ): Element {
        val raw = rawBounds(node)
        val left = raw.left.coerceIn(0, width)
        val right = raw.right.coerceIn(0, width)
        val top = raw.top.coerceIn(0, height)
        val bottom = raw.bottom.coerceIn(0, height)
        val password = node.bool("isPassword")
        return Element(
            index = index,
            role = roleFor(node),
            text = if (password) null else visibleText(node).ifEmpty { null },
            contentDescription = node.str("contentDescription").ifEmpty { node.str("hint") }.ifEmpty { null },
            resourceId = node.str("resourceId").ifEmpty { null },
            bounds = Bounds(left, top, maxOf(right, left), maxOf(bottom, top)),
            clickable = node.bool("isClickable"),
            longClickable = node.bool("isLongClickable"),
            editable = node.bool("isEditable"),
            scrollable = node.bool("isScrollable"),
            checkable = node.bool("isCheckable"),
            checked = node.bool("isChecked"),
            focused = node.bool("isFocused"),
            enabled = (node["isEnabled"] as? JsonPrimitive)?.booleanOrNull != false,
            selected = node.bool("isSelected"),
            password = password,
            depth = if (parent == null) 0 else elements[parent].depth + 1,
            parentIndex = parent,
        )
    }

    private fun visibleText(node: JsonObject) = if (node.bool("isShowingHintText")) "" else node.str("text")

    private fun activity(
        activity: String,
        packageName: String,
    ): String? {
        val relative =
            if (packageName.isNotEmpty() && activity.startsWith("$packageName.")) {
                activity.removePrefix(packageName)
            } else {
                activity
            }
        return relative.takeIf { activityRe.matches(it) }
    }

    private fun rawBounds(node: JsonObject): Bounds {
        val b = node.obj("boundsInScreen")
        return Bounds(b.int("left") ?: 0, b.int("top") ?: 0, b.int("right") ?: 0, b.int("bottom") ?: 0)
    }

    private fun children(node: JsonObject): List<JsonObject> = (node["children"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject ?: JsonObject(emptyMap())

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()

    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true

    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
}
