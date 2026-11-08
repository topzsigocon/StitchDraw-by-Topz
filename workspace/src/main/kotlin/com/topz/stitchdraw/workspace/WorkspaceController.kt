package com.topz.stitchdraw.workspace

/** Backing store for workspace JSON (app implements this with internal files / SAF). */
interface WorkspaceStorage {
    fun read(): String?
    fun write(text: String)
}

/**
 * Holds the live workspace arrangement plus the user's saved layouts.
 *
 * INDEPENDENCE CONTRACT: this class and everything in this module has no dependency on the
 * embroidery, digitizing or vector engines. It exchanges nothing with them except
 * [QuickCommand] / [com.topz.stitchdraw.workspace.ContextMenuItem] identifiers that the host app interprets.
 * Rearranging panels therefore cannot change, cancel or interrupt a digitizing operation.
 */
class WorkspaceController(initial: WorkspaceLayout = WorkspacePresets.default()) {

    fun interface Listener { fun onLayoutChanged(old: WorkspaceLayout, new: WorkspaceLayout) }

    var layout: WorkspaceLayout = WorkspaceReducer.normalize(initial)
        private set

    private val listeners = LinkedHashSet<Listener>()
    private val saved = LinkedHashMap<String, WorkspaceLayout>()

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    /** Applies [action]; returns true if the arrangement actually changed. */
    fun dispatch(action: WorkspaceAction): Boolean {
        val old = layout
        val new = WorkspaceReducer.reduce(old, action)
        if (new === old || new == old) return false
        layout = new
        for (l in listeners.toList()) l.onLayoutChanged(old, new)
        return true
    }

    // ---- presets and saved layouts --------------------------------------------------------

    /** Applies a built-in preset or a user-saved layout, keeping touch options the user chose. */
    fun applyPreset(name: String): Boolean {
        val target = saved[name] ?: WorkspacePresets.create(name) ?: return false
        val keep = layout
        return dispatch(
            WorkspaceAction.Replace(
                target.copy(
                    name = name,
                    quickToolbar = keep.quickToolbar,
                    stylusOnlyDrawing = keep.stylusOnlyDrawing,
                    // Beginner uses larger icons; other built-ins keep the user's chosen icon size.
                    iconScale = if (WorkspacePresets.isBuiltIn(name) && name != WorkspacePresets.BEGINNER) keep.iconScale else target.iconScale
                )
            )
        )
    }

    /** Saves the current arrangement under [name]. Built-in names are reserved. */
    fun saveCurrentAs(name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty() || WorkspacePresets.isBuiltIn(clean)) return false
        saved[clean] = layout.copy(name = clean, focusMode = false)
        layout = layout.copy(name = clean)
        return true
    }

    fun deleteSaved(name: String): Boolean = saved.remove(name) != null

    fun savedNames(): List<String> = saved.keys.toList()
    fun allPresetNames(): List<String> = WorkspacePresets.builtInNames + saved.keys

    // ---- persistence ----------------------------------------------------------------------

    fun save(storage: WorkspaceStorage) {
        storage.write(WorkspaceSerializer.encodeState(layout, saved))
    }

    /** Restores layout and saved layouts. Corrupt or missing data falls back to the current layout. */
    fun restore(storage: WorkspaceStorage): Boolean {
        val text = try { storage.read() } catch (e: Exception) { null } ?: return false
        val state = WorkspaceSerializer.decodeState(text) ?: return false
        saved.clear()
        saved.putAll(state.second)
        dispatch(WorkspaceAction.Replace(state.first))
        return true
    }
}
