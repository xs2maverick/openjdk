#! /bin/sh

# Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.  Oracle designates this
# particular file as subject to the "Classpath" exception as provided
# by Oracle in the LICENSE file that accompanied this code.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
# version 2 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# You should have received a copy of the GNU General Public License version
# 2 along with this work; if not, write to the Free Software Foundation,
# Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
# or visit www.oracle.com if you need additional information or have any
# questions.

# @test
# @summary Tests that service creation is invariant to the class loader
# @run shell classloader.sh

set -e

BIN=${TESTJAVA:-../../../../build}/bin
SRC=${TESTSRC:-.}
VMOPTS="${TESTVMOPTS} -esa -ea"

mk() {
  d=`dirname $1`
  if [ ! -d $d ]; then mkdir -p $d; fi
  cat - >$1
}

rm -rf z.*

mk z.src/foo/module-info.java <<EOF
module foo@1.0 {
    exports com.foo;
}
EOF

mk z.src/foo/com/foo/Provider.java <<EOF
package com.foo;
public abstract class Provider {
    protected Provider() { }
}
EOF

mk z.src/bar/module-info.java  <<EOF
module bar@1.0 {
    requires foo;
    provides service com.foo.Provider with com.bar.MyProvider;
}
EOF

mk z.src/bar/com/bar/MyProvider.java <<EOF
package com.bar;
import com.foo.Provider;
public class MyProvider extends Provider {
    public MyProvider() { }
}
EOF

mk z.src/gus/module-info.java <<EOF
module gus @ 1.0 {
    requires foo;
    requires service com.foo.Provider;
    class com.gus.Main;
}
EOF

mk z.src/gus/com/gus/Main.java <<EOF
package com.gus;
import java.util.*;
import com.foo.Provider;
public class Main {
    public static void main(String[] args) {
        int expected = Integer.parseInt(args[0]);
        test(expected, Provider.class.getClassLoader());
        test(expected, ClassLoader.getSystemClassLoader());
        test(expected, null);
    }

    private static void test(int expected, ClassLoader loader) {
        System.out.format("Using class loader %s%n", loader);
        ServiceLoader<Provider> sl = ServiceLoader.load(Provider.class, loader); 
        int count = 0;
        for (Provider p: sl) {
            System.out.format("%s loaded by %s%n", p.getClass(), p.getClass().getClassLoader());
            count++;
        }
        if (count != expected)
            throw new RuntimeException(expected + " providers expected");
    }
}
EOF

mkdir z.modules z.classes

$BIN/javac -source 8 -d z.modules -modulepath z.modules \
    `find z.src -name '*.java'`
$BIN/jmod ${TESTTOOLVMOPTS} -L z.lib create
$BIN/jmod ${TESTTOOLVMOPTS} -L z.lib install z.modules `ls z.src`
$BIN/jmod ${TESTTOOLVMOPTS} -L z.lib ls -v
$BIN/java ${VMOPTS} -L z.lib -m gus 1
