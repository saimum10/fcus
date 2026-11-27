package com.focus.app.domain

/**
 * The four product modes. Whether a mode can really be enforced depends on the
 * deployment mode (regular device vs. Device Owner) and is decided in Phase 5;
 * this type only describes what the person asked for.
 */
enum class FocusMode { SelectedApps, FullFocus, CallOnly, ScheduledFocus }
