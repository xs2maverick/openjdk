/*
 * Copyright (c) 2007, 2010, Oracle and/or its affiliates. All rights reserved.
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

package sun.launcher;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.InvocationTargetException;
import java.util.ResourceBundle;
import java.text.MessageFormat;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.Arrays;


/*
 * <p><b>This is NOT part of any API supported by Sun Microsystems.
 * If you write code that depends on this, you do so at your own
 * risk.  This code and its internal interfaces are subject to change
 * or deletion without notice.</b>
 */

/**
 * A utility package for the java(1), javaw(1) launchers.
 * The following are helper methods that the native launcher uses
 * to perform checks etc. using JNI, see src/share/bin/java.c
 */

public class LauncherHelper {

    private static final String defaultBundleName =
            "sun.launcher.resources.launcher";

    private static class ResourceBundleHolder {
        private static final ResourceBundle RB
            = ResourceBundle.getBundle(defaultBundleName);
    }

    private static final String MAIN_CLASS = "Main-Class";
    private static StringBuilder outBuf = new StringBuilder();

    /**
     * A private helper method to get a localized message and also
     * apply any arguments that we might pass.
     */
    private static String getLocalizedMessage(String key, Object ... args) {
        String msg = ResourceBundleHolder.RB.getString(key);
        return (args != null) ? MessageFormat.format(msg, args) : msg;
    }

    /**
     * The java -help message is split into 3 parts, an invariant, followed
     * by a set of platform dependent variant messages, finally an invariant
     * set of lines.
     * This method initializes the help message for the first time, and also
     * assembles the invariant header part of the message.
     */
    static void initHelpMessage(String progname) {
        outBuf = outBuf.append(getLocalizedMessage("java.launcher.opt.header",
                (progname == null) ? "java" : progname ));
        outBuf = outBuf.append(getLocalizedMessage("java.launcher.opt.datamodel",
                32));
        outBuf = outBuf.append(getLocalizedMessage("java.launcher.opt.datamodel",
                64));
    }

    /**
     * Appends the vm selection messages to the header, already created.
     * initHelpSystem must already be called.
     */
    static void appendVmSelectMessage(String vm1, String vm2) {
        outBuf = outBuf.append(getLocalizedMessage("java.launcher.opt.vmselect",
                vm1, vm2));
    }

    /**
     * Appends the vm synoym message to the header, already created.
     * initHelpSystem must be called before using this method.
     */
    static void appendVmSynonymMessage(String vm1, String vm2) {
        outBuf = outBuf.append(getLocalizedMessage("java.launcher.opt.hotspot",
                vm1, vm2));
    }

    /**
     * Appends the vm Ergo message to the header, already created.
     * initHelpSystem must be called before using this method.
     */
    static void appendVmErgoMessage(boolean isServerClass, String vm) {
        outBuf = outBuf.append(getLocalizedMessage("java.launcher.ergo.message1",
                vm));
        outBuf = (isServerClass)
             ? outBuf.append(",\n" +
                getLocalizedMessage("java.launcher.ergo.message2") + "\n\n")
             : outBuf.append(".\n\n");
    }

    /**
     * Appends the last invariant part to the previously created messages,
     * and finishes up the printing to the desired output stream.
     * initHelpSystem must be called before using this method.
     */
    static void printHelpMessage(boolean printToStderr) {
        PrintStream ostream = (printToStderr) ? System.err : System.out;
        outBuf = outBuf.append(getLocalizedMessage("java.launcher.opt.footer",
                File.pathSeparator));
        ostream.println(outBuf.toString());
    }

    /**
     * Prints the Xusage text to the desired output stream.
     */
    static void printXUsageMessage(boolean printToStderr) {
        PrintStream ostream =  (printToStderr) ? System.err : System.out;
        ostream.println(getLocalizedMessage("java.launcher.X.usage",
                File.pathSeparator));
    }

    static String getMainClassFromJar(String jarname) throws IOException {
        JarFile jarFile = null;
        try {
            jarFile = new JarFile(jarname);
            Manifest manifest = jarFile.getManifest();
            if (manifest == null) {
                throw new IOException("manifest not found in " + jarname);
            }
            Attributes mainAttrs = manifest.getMainAttributes();
            if (mainAttrs == null) {
                throw new IOException("no main mainifest attributes, in " +
                        jarname);
            }
            return mainAttrs.getValue(MAIN_CLASS).trim();
        } finally {
            if (jarFile != null) {
                jarFile.close();
            }
        }
    }

    // From src/share/bin/java.c:
    //   enum LaunchMode { LM_UNKNOWN = 0, LM_CLASS, LM_JAR, LM_MODULE };

    private static final int LM_UNKNOWN = 0;
    private static final int LM_CLASS   = 1;
    private static final int LM_JAR     = 2;
    private static final int LM_MODULE  = 3;

    /**
     * This method does the following:
     * 1. gets the classname from a Jar's manifest, if necessary
     * 2. loads the class using the System ClassLoader
     * 3. ensures the availability and accessibility of the main method:
     *    a. does the class exist
     *    b. is there a main
     *    c. is the main public
     *    d. is the main static
     *    c. does the main take a String array for args
     * 4. and off we go......
     */
    public static Class checkAndLoadMain(int mode, String what)
        throws IOException
    {

        ClassLoader ld = ClassLoader.getSystemClassLoader();

        // get the class name
        String cn = null;
        switch (mode) {
        case LM_CLASS:
            cn = what;
            break;
        case LM_JAR:
            cn = getMainClassFromJar(what);
            break;
        case LM_MODULE:
            cn = org.openjdk.jigsaw.Launcher.mainClass(ld);
            break;
        default:
            throw new InternalError("" + mode + ": Unknown launch mode");
        }
        cn = cn.replace('/', '.');

        Class<?> c = null;
        try {
            c = ld.loadClass(cn);
        } catch (ClassNotFoundException cnfe) {
            System.err.println(getLocalizedMessage("java.launcher.cls.error1",
                                                   cn));
            NoClassDefFoundError ncdfe = new NoClassDefFoundError(cn);
            ncdfe.initCause(cnfe);
            throw ncdfe;
        }
        checkMainSignature(System.err, c);

        return c;

    }

    static Method checkMainSignature(PrintStream ostream, Class<?> c) {
        String classname = c.getName();
        Method method = null;
        try {
            method = c.getMethod("main", String[].class);
        } catch (NoSuchMethodException nsme) {
            ostream.println(getLocalizedMessage("java.launcher.cls.error4",
                    classname));
            throw new RuntimeException("Main method not found in " + classname);
        }
        /*
         * getMethod (above) will choose the correct method, based
         * on its name and parameter type, however, we still have to
         * ensure that the method is static and returns a void.
         */
        int mod = method.getModifiers();
        if (!Modifier.isStatic(mod)) {
            ostream.println(getLocalizedMessage("java.launcher.cls.error2",
                    "static", classname));
            throw new RuntimeException("Main method is not static in class " +
                    classname);
        }
        if (method.getReturnType() != java.lang.Void.TYPE) {
            ostream.println(getLocalizedMessage("java.launcher.cls.error3",
                    classname));
            throw new RuntimeException("Main method must return a value" +
                    " of type void in class " +
                    classname);
        }
        return method;
    }

    /**
     * ## Entry point for tool module that launches the JDK tools
     * ## Temporary until multiple entry points is supported in modules
     * 
     * @params argv the main classname of the tool at the first element
     *     (argv[0]) and the remaining elements are the input arguments
     *     to the tools.
     */
    public static void main(String[] argv) throws Throwable {
        int argc = argv.length;

        String cn = argv[0];
        try {
            // use the system class loader to find the tool's main class
            Class<?> c = Class.forName(cn, true, ClassLoader.getSystemClassLoader());
            Method m = checkMainSignature(System.err, c);
            String[] args = argc == 1 ? 
                                new String[0] :
                                Arrays.copyOfRange(argv, 1, argc);
            m.invoke(null, (Object) args);
        } catch (ClassNotFoundException cnfe) {
            System.err.println(getLocalizedMessage("java.launcher.cls.error1",
                                                   cn));
            NoClassDefFoundError ncdfe = new NoClassDefFoundError(cn);
            ncdfe.initCause(cnfe);
            throw ncdfe;
        } catch (InvocationTargetException ite) {
            throw ite.getCause();
        }
    }
}
