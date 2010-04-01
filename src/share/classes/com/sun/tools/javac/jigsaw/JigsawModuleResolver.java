/*
 * Copyright 2009 Sun Microsystems, Inc.  All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Sun designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Sun in the LICENSE file that accompanied this code.
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
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 */

package com.sun.tools.javac.jigsaw;

import java.io.File;
import java.io.IOException;
import java.lang.module.ModuleId;
import java.lang.module.ModuleIdQuery;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.Collection;
import java.util.LinkedHashSet;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.util.ModuleResolver;
import javax.tools.JavaFileManager;

import org.openjdk.jigsaw.Configuration;
import org.openjdk.jigsaw.ConfigurationException;
import org.openjdk.jigsaw.Configurator;
import org.openjdk.jigsaw.JigsawModuleSystem;
import org.openjdk.jigsaw.PathContext;
import org.openjdk.jigsaw.Platform;

import com.sun.tools.javac.code.Symbol.ModuleSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.main.OptionName;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;
import com.sun.tools.javac.util.Options;
import javax.tools.JavaFileManager.Location;

/**
 * Jigsaw implementation of javac's simple abstraction of a module resolver.
 */
public class JigsawModuleResolver implements ModuleResolver {
    JigsawModuleSystem jigsaw;
    JavacCatalog catalog;
    Configuration<PathContext> config;
    Names names;
    Symtab syms;
    JavaFileManager fileManager;

    boolean DEBUG = (System.getProperty("javac.debug.modules") != null);
    void DEBUG(String s) {
        if (DEBUG)
            System.err.println(s);
    }

    public JigsawModuleResolver(Context context) throws IOException/*FIXME*/ {
        jigsaw = JigsawModuleSystem.instance();

        names = Names.instance(context);
        syms = Symtab.instance(context);

        Options options = Options.instance(context);
        String l = options.get(OptionName.L);
        File library = (l == null ? null : new File(l));

        catalog = new JavacCatalog(library);
    }

    public Iterable<? extends ModuleElement> resolve(
            Iterable<? extends ModuleElement> roots,
            Iterable<? extends ModuleElement> modules)
            throws ResolutionException {
        DEBUG("JigsawModuleResolver.resolve");
        catalog.init(modules);
        Collection<ModuleIdQuery> rootQueries = new LinkedHashSet<ModuleIdQuery>();
        for (ModuleElement r: roots) {
            // should use catalog here
	    CharSequence rn = r.getModuleId().getName();
            String n = rn.toString();
	    CharSequence rv = r.getModuleId().getVersion();
            String v = (rv == null) ? null : rv.toString();
            String q = (v == null) ? n : (n + "@" + v);
            rootQueries.add(jigsaw.parseModuleIdQuery(q)); // FIXME: handle IllegalArgumentException
        }
        try {
            config = Configurator.configurePaths(catalog, rootQueries);
        } catch (IOException e) {
            throw new ResolutionException(); // FIXME: propogate e
        } catch (ConfigurationException e) {
            e.printStackTrace(); // FIXME
            throw new ResolutionException(); // FIXME: propogate e
        }
            // have config.roots: set of root module ids
            // have config.contexts: set of preconfigured PathContexts
            // need to flatten the contexts graphs, using tarjan
            // then build List<ModuleElement> for return.
            // some ModuleElement will come from the input list
            // other ModuleElement will have to come from library: create these
            // symbols as uncompleted ModuleSymbols, with completer set to ClassReader
            // Will need new LibraryLocation interface in ModuleFileManager
            // for use by modules in library.    Means that FileManager.join
            // has to be able to cope.
        ListBuffer<PathContext> rootContexts = new ListBuffer<PathContext>();
        for (ModuleId mid: config.roots()) {
            PathContext pcx = config.getContextForModuleName(mid.name());
            rootContexts.add(pcx);
        }
        Tarjan<PathContext> t = new Tarjan<PathContext>(rootContexts) {
            @Override
            protected Iterable<? extends PathContext> getDependencies(PathContext pcx) {
                return pcx.remoteContexts();
            }
        };
        ListBuffer<ModuleElement> results = new ListBuffer<ModuleElement>();
        for (PathContext pcx: t.list()) {
            for (ModuleId mid: pcx.localPath()) {
                ModuleSymbol sym = catalog.getModuleSymbol(mid);
                if (sym == null) {
                    Name name = names.fromString(mid.name());
                    sym = new ModuleSymbol(name, syms.rootModule);
                    sym.version = names.fromString(mid.version().toString());
                    sym.location = new JigsawLibraryLocation(catalog.library, mid);
                }
                results.add(sym);
            }
        }

        // Update the path to accommodate any user specified -Xbootclasspath* options.
        // When JavacPathFileManager implements ModuleFileManager, the following
        // uses of JavacFileManager should be updated to use BaseFileManager
        // i.e. the common supertype of JavacFileManager and JavacPathFileManager
        if (fileManager instanceof JavacFileManager) {
            JavacFileManager jfm = (JavacFileManager) fileManager;
            // determine first and last platform location, for placing
            // -Xbootclasspath/p: and -Xbootclasspath/a:
            Location firstPlatform = null;
            Location lastPlatform = null;
            for (ModuleElement e: results) {
                ModuleSymbol msym = (ModuleSymbol) e;
                if (isPlatformName(msym.fullname)) {
                    if (firstPlatform == null)
                        firstPlatform = msym.location;
                    lastPlatform = msym.location;
                }
            }
            // update locations as needed
            for (ModuleElement e: results) {
                ModuleSymbol msym = (ModuleSymbol) e;
                if (isPlatformName(msym.fullname)) {
                    msym.location = jfm.augmentPlatformLocation(msym.location,
                            msym.location == firstPlatform,
                            msym.location == lastPlatform);
                }
            }

            DEBUG("JigsawModuleResolver.resolve UPDATED PATH");
            for (ModuleElement e: results) {
                ModuleSymbol msym = (ModuleSymbol) e;
                DEBUG("JigsawModuleResolver.resolve " + msym + " " + msym.location);
            }
            DEBUG("JigsawModuleResolver.resolve UPDATED PATH done");
        }

        DEBUG("JigsawModuleResolver.resolve done");
        return results;
    }

    public Iterable<? extends ModuleElement> getVisibleModules(ModuleElement module)
            throws IllegalStateException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public boolean isPlatformName(CharSequence name) {
        return Platform.isPlatformModuleName(name.toString());
    }

    public String getDefaultPlatformModule() {
        return Platform.defaultPlatformModule().toString();
    }


    static abstract class Tarjan<T> {
        protected Tarjan(Iterable<T> roots) {
            this.roots = roots;
        }

        /**
         * Given a set of roots defining a directed graph, return an ordered
         * list of all the nodes in the graph, such that if node A depends on
         * node B, and there is no cycle between A and B, then A appears before
         * B in the list.
         * @return
         */
        public Iterable<? extends T> list() {
            List<Node> rootNodes = getNodes(roots);
            for (Node node: rootNodes) {
                if (node.index == -1)
                    tarjan(node);
            }
            LinkedHashSet<T> results = new LinkedHashSet<T>();
            for (Node node: rootNodes) {
                if (!results.contains(node.t))
                    list(node.scc, results);
            }
            return Collections.unmodifiableSet(results);
        }

        /**
         * Get the set of graph nodes on which this node depends.
         * @param t
         * @return
         */
        protected abstract Iterable<? extends T> getDependencies(T t);

        protected String toString(T t) {
            return t.toString();
        }

        private void list(SCC scc, LinkedHashSet<T> results) {
            for (Node n: scc.nodes)
                results.add(n.t);
            for (SCC child: scc.getChildren())
                list(child, results);
        }

        private List<Node> getNodes(Iterable<? extends T> elems) {
            ListBuffer<Node> lb = new ListBuffer<Node>();
            for (T elem: elems) {
                lb.add(getNode(elem));
            }
            return lb.toList();
        }

        private Node getNode(T sym) {
            Node node = nodeMap.get(sym);
            if (node == null)
                nodeMap.put(sym, (node = new Node(sym)));
            return node;
        }
        // where
        private Map<T, Node> nodeMap= new HashMap<T, Node>();


        // Tarjan's algorithm to determine strongly connected components of a
        // directed graph in linear time.

        void tarjan(Node v) {
            v.index = index;
            v.lowlink = index;
            index++;
            stack.add(0, v);
            v.active = true;
            for (Node n: v.getDependencies()) {
                if (n.index == -1) {
                    tarjan(n);
                    v.lowlink = Math.min(v.lowlink, n.lowlink);
                } else if (stack.contains(n)) {
                    v.lowlink = Math.min(v.lowlink, n.index);
                }
            }
            if (v.lowlink == v.index) {
                Node n;
                SCC scc = new SCC();
                do {
                    n = stack.remove(0);
                    n.active = false;
                    scc.add(n);
                } while (n != v);
            }
        }

        private final Iterable<T> roots;
        private int index = 0;
        private ArrayList<Node> stack = new ArrayList<Node>();

        private class Node implements Comparable<Node> {
            final T t;
            SCC scc;
            int index = -1;
            int lowlink;
            boolean active;

            Node(T t) {
                this.t = t;
            }

            Iterable<Node> getDependencies() {
                ListBuffer<Node> nodes = new ListBuffer<Node>();
                for (T dep: Tarjan.this.getDependencies(this.t))
                    nodes.add(getNode(dep));
                return nodes.toList();
            }

            @Override
            public String toString() {
                return Tarjan.this.toString(t) + "(index:" + index +",low:" + lowlink + ",active:" + active + ")" ;
            }

            public int compareTo(Node o) {
                return (index < o.index) ? -1 : (index == o.index) ? 0 : 1;
            }
        }

        private class SCC {
            void add(Node node) {
                nodes.add(node);
                node.scc = this;
            }

            Set<SCC> getChildren() {
                if (children == null) {
                    children = new LinkedHashSet<SCC>();
                    for (Node node: nodes) {
                        for (Node n: node.getDependencies()) {
                            n.scc.getClass(); // nullcheck
                            if (n.scc != this)
                                children.add(n.scc);
                        }
                    }
                }
                return children;
            }

            @Override
            public String toString() {
                return nodes.toString();
            }

            private SortedSet<Node> nodes = new TreeSet<Node>();
            private Set<SCC> children;
        }
    }

}
