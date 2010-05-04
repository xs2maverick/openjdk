/*
 * Copyright 2009-2010 Sun Microsystems, Inc.  All Rights Reserved.
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

// ## TODO: Work through security and concurrency issues

package org.openjdk.jigsaw;

import java.lang.module.*;
import java.lang.reflect.Module;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.*;

import static org.openjdk.jigsaw.Trace.*;


public class Loader
    extends ModuleClassLoader
{

    protected final LoaderPool pool;
    private final Context context;

    private Map<String,Module> moduleForName
        = new HashMap<String,Module>();

    protected final Set<ModuleId> modules = new HashSet<ModuleId>();

    public Loader(LoaderPool p, Context cx) {
        super(JigsawModuleSystem.instance());
        if (cx == null)
            throw new IllegalArgumentException("Null context");
        pool = p;
        context = cx;
    }

    // Primary entry point from VM
    //
    protected Class<?> loadClass(String cn, boolean resolve) 
        throws ClassNotFoundException
    {
        // Check the loaded-class cache first.  The VM guarantees not to invoke
        // this method more than once for any given class name, but we still
        // need to check the cache manually in case this method is invoked by
        // user code.
        //
        Class<?> c = findLoadedClass(cn);
        if (c != null) {
            if (tracing) {
                trace(0, "%s: (cache) %s", this, cn);
            }
        } else {

            // Is the requested class local or remote?  It can be one or the other,
            // but not both.
            //
            String rcxn = context.findContextForRemoteClass(cn);
            ModuleId lmid = context.findModuleForLocalClass(cn);
            if (rcxn != null && lmid != null) {
                throw new AssertionError("Class " + cn
                                         + " defined both locally and remotely");
            }

            // Find a loader, and use that to load the class
            //
            Loader ld = null;
            if (lmid != null) {
                ld = this;
                if (tracing)
                    trace(0, "%s: load %s:%s", this, lmid, cn);
            } else if (rcxn != null) {
                ld = pool.findLoader(rcxn);
                if (tracing)
                    trace(0, "%s: load %s:%s", this, rcxn, cn);
            }
            if (ld == null) {
                throw new ClassNotFoundException(cn);
            }
            c = ld.findClass(lmid, cn);
        }
        if (resolve)
            resolveClass(c);
        return c;
    }

    // Invoked by findClass, below, and (eventually) by LoaderPool.init()
    //
    Module defineModule(ModuleId mid, byte[] bs) {
        Module m = super.defineModule(mid, bs, 0, bs.length);
        moduleForName.put(mid.name(), m);
        modules.add(mid);
        return m;
    }

    private ClassNotFoundException cnf(Module m, String cn, IOException x) {
        ClassNotFoundException cnfx
            = new ClassNotFoundException(m.getName() + ":" + cn);
        cnfx.initCause(x);
        return cnfx;
    }

    Class<?> findClass(ModuleId mid, String cn)
        throws ClassNotFoundException
    {

        if (mid == null) {
            mid = context.findModuleForLocalClass(cn);
            if (mid == null)
                throw new ClassNotFoundException(mid.name() + ":" + cn);
        }

        Class<?> c = findLoadedClass(cn);
        if (c != null) {
            ModuleId cmid = c.getModule().getModuleId();
            if (cmid == null || !cmid.equals(mid))
                throw new AssertionError(cn + " previously loaded from "
                                         + cmid + "; now trying to load from "
                                         + mid);
            return c;
        }

        // Have we defined this class's module yet?
        //
        Module m = moduleForName.get(mid.name());
        if (m != null) {
            if (!m.getModuleId().equals(mid))
                throw new AssertionError("Duplicate module in loader");
        }

        // Find the library from which we'll load the class
        //
        Library lib = null;
        try {
            lib = pool.library(context, mid);
        } catch (IOException x) {
            ClassNotFoundException cnfx
                = new ClassNotFoundException(mid.name() + ":" + cn);
            cnfx.initCause(x);
            throw cnfx;
        }

        // Define the module
        //
        if (m == null) {
            try {
                byte[] bs = lib.readLocalModuleInfoBytes(mid);
                if (bs == null)
                    throw new AssertionError();
                m = defineModule(mid, bs);
                if (tracing)
                    trace(0, "%s: define %s [%s]", this, mid, lib.name());
            } catch (IOException x) {
                throw cnf(m, cn, x);
            }
        }

        // The last step, of actually locating the class, is in a
        // separate method so that the kernel loader can override it
        //
        return finishFindingClass(lib, mid, m, cn);

    }

    Class<?> finishFindingClass(Library lib, ModuleId mid, Module m, String cn)
        throws ClassNotFoundException
    {

        try {
            byte[] bs = lib.readLocalClass(mid, cn);
            if (bs == null)
                throw new ClassNotFoundException(mid + ":" + cn);
            Class<?> c = defineClass(m, cn, bs, 0, bs.length);
            if (tracing)
                trace(0, "%s: define %s:%s [%s]", this, mid, cn, lib.name());
            return c;
        } catch (IOException x) {
            throw cnf(m, cn, x);
        }

    }

    public String toString() {
        return context.name();
    }


    // -- Native libraries --

    // Native libraries are, for now, discovered at run time.
    //
    // This could be made more efficient by instead identifying them
    // at module-link time and storing a map from library names to full
    // paths.

    @Override
    protected String findLibrary(String name) {
        String fn = System.mapLibraryName(name);
        IOException iox = null;
        try {
            for (ModuleId mid : context.modules()) {
                File nlf = (pool.library(context, mid)
                            .findLocalNativeLibrary(mid, fn));
                if (nlf != null) {
                    if (tracing)
                        trace(0, "%s: lib %s", this, nlf);
                    return nlf.getAbsolutePath();
                }
            }
        } catch (IOException x) {
            iox = x;
        }
        Error e = new UnsatisfiedLinkError("No library " + fn
                                           + " in module context "
                                           + context.name());
        if (iox != null)
            e.initCause(iox);
        throw e;
    }


    // -- Resources --

    // --
    //
    // The approach taken here is simply to discover resources at run time.
    // Given a resource name we first search this loader's modules for that
    // resource; we then search the loaders for every other context in this
    // configuration.  If we think of a Jigsaw configuration as a "better
    // class path" then this isn't completely unreasonable, but it is meant
    // to be temporary.
    //
    // An eventual fuller treatment of resources will treat them more like
    // classes, resolving them statically, allowing them to be declared
    // private to a module or public, and re-exporting them from one
    // context to another when "requires public" is used.
    //
    // --

    private static interface ResourceVisitor {
        // Return null to continue the search or a File to terminate
        // the search, returning that File
        public File accept(File f) throws IOException;
    }

    private File visitLocalResources(String rn, ResourceVisitor rv)
        throws IOException
    {
        for (ModuleId mid : context.modules()) {
            File f = pool.library(context, mid).findLocalResource(mid, rn);
            if (f != null) {
                f = rv.accept(f);
                if (f != null)
                    return f;
            }
        }
        return null;
    }

    private File visitResources(String rn, ResourceVisitor rv)
        throws IOException
    {
        // ## Should look up "platform" resources first,
        // ## in order to mimic current behavior
        File f = visitLocalResources(rn, rv);
        if (f != null)
            return f;
        for (Context cx : pool.config().contexts()) {
            if (context == cx)
                continue;
            f = pool.findLoader(cx).visitLocalResources(rn, rv);
            if (f != null)
                return f;
        }
        return null;
    }

    public URL getResource(String rn) {
        try {
            File f = visitResources(rn, new ResourceVisitor() {
                    public File accept(File f) {
                        return f;
                    }
                });
            if (f != null)
                return f.toURI().toURL();
            return null;
        } catch (IOException x) {
            // ClassLoader.getResource doesn't throw IOException (!)
            return null;
        }
    }

    public Enumeration<URL> getResources(String rn)
        throws IOException
    {
        final List<URL> fs = new ArrayList<URL>();
        visitResources(rn, new ResourceVisitor() {
                public File accept(File f) throws IOException {
                    fs.add(f.toURI().toURL());
                    return null;
                }
            });
        return Collections.enumeration(fs);
    }


    // -- Stubs for methods not yet re-implemented --

    /* ## Can't do this -- CL.getParent is final
    public ClassLoader getParent() {
        throw new UnsupportedOperationException();
    }
    */

    protected Package definePackage(String name, String specTitle,
                                    String specVersion, String specVendor,
                                    String implTitle, String implVersion,
                                    String implVendor, URL sealBase)
    {
        throw new UnsupportedOperationException();
    }

    protected Package getPackage(String name) {
        throw new UnsupportedOperationException();
    }

    protected Package[] getPackages() {
        throw new UnsupportedOperationException();
    }

}
