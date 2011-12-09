/*
 * Copyright (c) 2009, 2011, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.tools.javac.code;

import java.util.EnumSet;
import java.util.Set;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.TypeSymbol;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;

import static com.sun.tools.javac.code.Kinds.*;

/**
 *  Root class for the directives that may appear in module compilation units.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public abstract class Directive {
    public enum Kind {
        REQUIRES_MODULE,
        REQUIRES_SERVICE,
        PROVIDES_MODULE,
        PROVIDES_SERVICE,
        EXPORTS,
        PERMITS,
        ENTRYPOINT,
        VIEW
    }

    /** Flags for RequiresModuleDirective and RequiresServiceDirective. */
    public enum RequiresFlag {
        OPTIONAL(0x0001),
        LOCAL(0x0002),
        PUBLIC(0x0004);

        // overkill? move to ClassWriter?
        public static int value(Set<RequiresFlag> s) {
            int v = 0;
            for (RequiresFlag f: s)
                v |= f.value;
            return v;
        }
        
        RequiresFlag(int value) {
            this.value = value;
        }
        
        public final int value;
    }

    /** Flags for an ExportsDirective.
     * These are mutually exclusive; only one may be used in an ExportsDirective.
     */
    public enum ExportFlag {
        TYPE(0x0001),
        TYPE_AND_MEMBERS(0x0002),
        PACKAGE(0x0004),
        PACKAGE_AND_SUBPACKAGES(0x0008);

        ExportFlag(int value) {
            this.value = value;
        }

        // overkill? move to ClassWriter?
        public static int value(Set<ExportFlag> s) {
            int v = 0;
            for (ExportFlag f: s)
                v |= f.value;
            return v;
        }

        public static ExportFlag valueOf(int kind, boolean asterisk) {
            switch (kind) {
                case PCK:
                    return asterisk ? PACKAGE_AND_SUBPACKAGES : PACKAGE;
                case TYP:
                    return asterisk ? TYPE_AND_MEMBERS : TYPE;
                default:
                    throw new IllegalArgumentException();
            }
        }
        
        public final int value;
    }

    public abstract Kind getKind();

    static <T extends Directive> List<T> filter(ListBuffer<Directive> directives, Kind kind, Class<T> clazz) {
        ListBuffer<T> list = ListBuffer.lb();
        for (Directive d: directives) {
            if (d.getKind() == kind)
                list.add(clazz.cast(d));
        }
        return list.toList();
    }

    /**
     * 'requires' ['optional'] {'local' | 'public'} ModuleNameAndVersionQuery ';'
     */
    public static class RequiresModuleDirective extends Directive {
        public final ModuleIdQuery moduleQuery;
        public final Set<RequiresFlag> flags;

        public RequiresModuleDirective(ModuleIdQuery moduleQuery) {
            this(moduleQuery, EnumSet.noneOf(RequiresFlag.class));
        }

        public RequiresModuleDirective(ModuleIdQuery moduleQuery, Set<RequiresFlag> flags) {
            this.moduleQuery = moduleQuery;
            this.flags = flags;
        }

        @Override
        public Kind getKind() {
            return Kind.REQUIRES_MODULE;
        }

        @Override
        public String toString() {
            return "RequiresModule[" + flags + "," + moduleQuery + "]";
        }
    }

    /**
     * 'requires' ['optional'] 'service' ServiceName ';'
     */
    public static class RequiresServiceDirective extends Directive {
        public final ClassSymbol sym;
        public final Set<RequiresFlag> flags;

        public RequiresServiceDirective(ClassSymbol sym, Set<RequiresFlag> flags) {
            this.sym = sym;
            this.flags = flags;
        }

        @Override
        public Kind getKind() {
            return Kind.REQUIRES_SERVICE;
        }

        @Override
        public String toString() {
            return "RequiresService[" + flags + "," + sym + "]";
        }
    }

    /**
     * 'provides' ModuleNameAndVersion ';'
     */
    public static class ProvidesModuleDirective extends Directive {
        public final ModuleId moduleId;

        public ProvidesModuleDirective(ModuleId moduleId) {
            this.moduleId = moduleId;
        }

        @Override
        public Kind getKind() {
            return Kind.PROVIDES_MODULE;
        }

        @Override
        public String toString() {
            return "ProvidesModule[" + moduleId + "]";
        }
    }

    /**
     * 'provides' 'service' ServiceName 'with' QualifiedIdentifer ';'
     */
    public static class ProvidesServiceDirective extends Directive {
        public final ClassSymbol service;
        public final ClassSymbol impl;

        public ProvidesServiceDirective(ClassSymbol service, ClassSymbol impl) {
            this.service = service;
            this.impl = impl;
        }

        @Override
        public Kind getKind() {
            return Kind.PROVIDES_SERVICE;
        }

        @Override
        public String toString() {
            return "ProvidesService[" + service + "," + impl + "]";
        }
    }

    /**
     * 'exports' PackageOrTypeName ['.' '*'] ';'
     */
    public static class ExportsDirective extends Directive {
        public final TypeSymbol sym;
        public final Set<ExportFlag> flags;
        public final ModuleId origin;

        public ExportsDirective(TypeSymbol sym, Set<ExportFlag> flags, ModuleId origin) {
            this.sym = sym;
            this.flags = flags;
            this.origin = origin;
        }

        @Override
        public Kind getKind() {
            return Kind.EXPORTS;
        }

        @Override
        public String toString() {
            return "Exports[" + flags + "," + sym + "," + origin + "]";
        }
    }

    /**
     * 'permits' ModuleName ';'
     */
    public static class PermitsDirective extends Directive {
        public final ModuleId moduleId;

        public PermitsDirective(ModuleId moduleId) {
            this.moduleId = moduleId;
        }

        public PermitsDirective(Name moduleName) {
            moduleId = new ModuleId(moduleName);
        }

        @Override
        public Kind getKind() {
            return Kind.PERMITS;
        }

        @Override
        public String toString() {
            return "Permits[" + moduleId + "]";
        }
    }

    /**
     * 'class' TypeName ';'
     */
    public static class EntrypointDirective extends Directive {
        public final ClassSymbol sym;

        public EntrypointDirective(ClassSymbol sym) {
            this.sym = sym;
        }

        @Override
        public Kind getKind() {
            return Kind.ENTRYPOINT;
        }

        @Override
        public String toString() {
            return "Entrypoint[" + sym + "]";
        }
    }

    /**
     * 'view' ModuleName '{' {ProvidesDirective | ExportsDirective | PermitsDirective | EntrypointDirective} '}'
     */
    public static class ViewDeclaration extends Directive {
        public final Name name;
        public final ListBuffer<Directive> directives;
        
        public ViewDeclaration(Name name) {
            this.name = name;
            this.directives = ListBuffer.lb();
        }

        public ViewDeclaration(ListBuffer<Directive> directives) {
            this.name = null;
            this.directives = directives;
        }

        public boolean isDefault() {
            return name == null;
        }

        public boolean hasEntrypoint() {
            for (Directive d: directives) {
                if (d.getKind() == Directive.Kind.ENTRYPOINT)
                    return true;
            }
            return false;
        }

        public ClassSymbol getEntrypoint() {
            List<EntrypointDirective> list =
                    filter(directives, Kind.ENTRYPOINT, EntrypointDirective.class);
            return list.isEmpty() ? null : list.head.sym;
        }

        public List<ProvidesModuleDirective> getAliases() {
            return filter(directives, Kind.PROVIDES_MODULE, ProvidesModuleDirective.class);
        }

        public List<ProvidesServiceDirective> getServices() {
            return filter(directives, Kind.PROVIDES_SERVICE, ProvidesServiceDirective.class);
        }

        public List<ExportsDirective> getExports() {
            return filter(directives, Kind.EXPORTS, ExportsDirective.class);
        }

        public List<PermitsDirective> getPermits() {
            return filter(directives, Kind.PERMITS, PermitsDirective.class);
        }

        @Override
        public Kind getKind() {
            return Kind.VIEW;
        }

        @Override
        public String toString() {
            return "View[" + directives + "]";
        }
    }
}
