package com.aowmaps;

import aowtool.JarProject;

/** Holds the currently opened project between the two screens. */
final class Session {
    static JarProject project;
    private static JarProject artFor;
    private static ArtCache art;

    private Session() {}

    /** Game artwork decoded from the opened jar (null if the jar has none / decoding failed). */
    static synchronized ArtCache art() {
        if (project == null) return null;
        if (artFor != project) {
            artFor = project;
            art = ArtCache.create(project);
        }
        return art;
    }
}
