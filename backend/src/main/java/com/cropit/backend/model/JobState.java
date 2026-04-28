package com.cropit.backend.model;

public enum JobState {
    /** Job created, previews not yet generated. */
    PENDING,
    /** Previews ready; waiting for crop box submission. */
    PREVIEWS_READY,
    /** Full-page render is in progress. */
    RENDERING,
    /** Render completed successfully. */
    DONE,
    /** A processing error occurred. */
    ERROR
}
