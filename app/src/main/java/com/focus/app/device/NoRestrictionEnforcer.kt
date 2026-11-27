package com.focus.app.device

import com.focus.app.domain.Capability
import com.focus.app.domain.EndReason
import com.focus.app.domain.EnforcementResult
import com.focus.app.domain.FocusMode
import com.focus.app.domain.RestrictionEnforcer
import com.focus.app.domain.SessionPlan
import com.focus.app.domain.UnavailableReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The truthful enforcer for a setup with no restriction engine: every mode is reported as
 * unavailable, so the session engine refuses to start and nothing is ever shown as
 * restricted. Used by Phases 1-4; since Phase 5 the app wires `RestrictionCoordinator` instead.
 */
object NoRestrictionEnforcer : RestrictionEnforcer {
    override suspend fun capability(mode: FocusMode): Capability =
        Capability.Unavailable(UnavailableReason.EngineMissing)

    override suspend fun apply(plan: SessionPlan): EnforcementResult =
        EnforcementResult.Failed(EndReason.DeviceUnsupported)

    override suspend fun release() = Unit

    override val losses: Flow<EndReason> = emptyFlow()
}
