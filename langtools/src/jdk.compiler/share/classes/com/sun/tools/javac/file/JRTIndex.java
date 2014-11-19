/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.sun.tools.javac.file;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.SoftReference;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.sun.tools.javac.file.RelativePath.RelativeDirectory;
import com.sun.tools.javac.util.Context;

/**
 * A package-oriented index into the jrt: filesystem.
 */
class JRTIndex {
    /** Get a shared instance of the cache. */
    private static JRTIndex sharedInstance;
    public synchronized static JRTIndex getSharedInstance() throws IOException {
        if (sharedInstance == null) {
            sharedInstance = new JRTIndex() {
                @Override
                public void close() { }
            };
        }
        return sharedInstance;
    }

    /** Get a context-specific instance of a cache. */
    public static JRTIndex instance(Context context) {
        try {
            JRTIndex instance = context.get(JRTIndex.class);
            if (instance == null)
                context.put(JRTIndex.class, instance = new JRTIndex());
            return instance;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }


    /**
     * The jrt: file system.
     */
    private final FileSystem jrtfs;

    /**
     * The set of module directories within the jrt: file system.
     */
    private final Set<Path> jrtModules;

    /**
     * A lazily evaluated set of entries about the contents of the jrt: file system.
     */
    private final Map<RelativeDirectory, SoftReference<Entry>> entries;

    /**
     * An entry provides cached info about a specific package directory within jrt:.
     */
    class Entry {
        /**
         * The regular files for this package.
         * For now, assume just one instance of each file across all modules.
         */
        final Map<String, Path> files;

        /**
         * The set of subdirectories in jrt: for this package.
         */
        final Set<RelativeDirectory> subdirs;

        private Entry(Map<String, Path> files, Set<RelativeDirectory> subdirs) {
            this.files = files;
            this.subdirs = subdirs;
        }
    }

    /**
     * Create and initialize the index.
     */
    private JRTIndex() throws IOException {
        jrtfs = FileSystems.newFileSystem(URI.create("jrt:/"), null);
        jrtModules = new LinkedHashSet<>();
        Path root = jrtfs.getPath("/");
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path entry: stream) {
                if (Files.isDirectory(entry))
                    jrtModules.add(entry);
            }
        }
        entries = new HashMap<>();
    }

    synchronized Entry getEntry(RelativeDirectory rd) throws IOException {
        SoftReference<Entry> ref = entries.get(rd);
        Entry e = (ref == null) ? null : ref.get();
        if (e == null) {
            Map<String, Path> files = new LinkedHashMap<>();
            Set<RelativeDirectory> subdirs = new LinkedHashSet<>();
            for (Path module: jrtModules) {
                Path p = rd.getFile(module);
                if (!Files.exists(p))
                    continue;
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(p)) {
                    for (Path entry: stream) {
                        String name = entry.getFileName().toString();
                        if (Files.isRegularFile(entry)) {
                            // TODO: consider issue of files with same name in different modules
                            files.put(name, entry);
                        } else if (Files.isDirectory(entry)) {
                            subdirs.add(new RelativeDirectory(rd, entry.getFileName().toString()));
                        }
                    }
                }
            }
            e = new Entry(Collections.unmodifiableMap(files), Collections.unmodifiableSet(subdirs));
            entries.put(rd, new SoftReference<>(e));
        }
        return e;
    }

    synchronized void close() throws IOException {
        jrtfs.close();
        jrtModules.clear();
        entries.clear();
    }
}
