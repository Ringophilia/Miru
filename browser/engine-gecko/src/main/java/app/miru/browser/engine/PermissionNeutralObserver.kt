// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.permission.PermissionRequest

/**
 * A navigation/UI observer must not resolve another feature's permission request.
 * AC 158's Observer defaults reject both request types. Real browser tabs instead
 * have one BrowserPermissions owner; these callbacks only leave that owner alone.
 * Popup and inert preparation sessions deliberately retain the default rejection.
 */
internal abstract class PermissionNeutralObserver : EngineSession.Observer {
    final override fun onAppPermissionRequest(permissionRequest: PermissionRequest) = Unit
    final override fun onContentPermissionRequest(permissionRequest: PermissionRequest) = Unit
}
