package com.topz.stitchdraw.workspace

/** Every way the arrangement of the workspace can change. None of them can reach the embroidery engine. */
sealed interface WorkspaceAction {
    // Collapse / expand
    data class SetCollapsed(val id: PanelId, val collapsed: Boolean) : WorkspaceAction
    data class ToggleCollapsed(val id: PanelId) : WorkspaceAction
    data class SetZoneCollapsed(val zone: DockZone, val collapsed: Boolean) : WorkspaceAction

    // Open / close
    data class Open(val id: PanelId) : WorkspaceAction
    data class Close(val id: PanelId) : WorkspaceAction

    // Dock / float
    data class Dock(val id: PanelId, val zone: DockZone, val index: Int? = null) : WorkspaceAction
    data class Undock(val id: PanelId, val rect: NRect? = null) : WorkspaceAction
    data class Reorder(val id: PanelId, val index: Int) : WorkspaceAction

    // Floating geometry
    data class SetFloatRect(val id: PanelId, val rect: NRect) : WorkspaceAction
    data class SetMinimized(val id: PanelId, val minimized: Boolean) : WorkspaceAction
    data class SetMaximized(val id: PanelId, val maximized: Boolean) : WorkspaceAction
    data class SetPinned(val id: PanelId, val pinned: Boolean) : WorkspaceAction
    data class Raise(val id: PanelId) : WorkspaceAction

    // Zones
    data class ResizeZone(val zone: DockZone, val size: Float) : WorkspaceAction

    // Focus mode and floating controls
    data class SetFocus(val on: Boolean) : WorkspaceAction
    data object ToggleFocus : WorkspaceAction
    data class MoveFocusButton(val pos: NPoint) : WorkspaceAction
    data class MoveQuickToolbar(val pos: NPoint) : WorkspaceAction
    data class SetQuickToolbar(val commands: List<QuickCommand>) : WorkspaceAction

    // Touch / mode options
    data class SetIconScale(val scale: Float) : WorkspaceAction
    data class SetMode(val mode: UserMode) : WorkspaceAction
    data class SetStylusOnly(val on: Boolean) : WorkspaceAction

    /** Replace the whole arrangement (preset or restored layout). */
    data class Replace(val layout: WorkspaceLayout) : WorkspaceAction
}
