/*
 * Copyright 2009 Sun Microsystems, Inc.  All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

/*
 * @test
 * @bug 6802521
 * @summary add support for modules: test basic use of ModuleClass attribute
 */

import java.io.*;
import java.util.*;
import com.sun.tools.classfile.*;

public class ModuleClassAttributeTest01 {
    String[][] values = {
        {"C"},
        {"main", "my.pckge.Main"},
        {"fx", "applet", "a.b.c.MyClass"}
    };

    public static void main(String[] args) throws Exception {
        new ModuleClassAttributeTest01().run();
    }

    void run() throws Exception {
        for (String[] v: values) {
            try {
                String[] flags = null;
                String className = null;
                if (v.length > 0) {
                    flags = new String[v.length - 1];
                    System.arraycopy(v, 0, flags, 0, flags.length);
                    className = v[v.length - 1];
                }
                System.err.println("Test " + Arrays.asList(flags) + " " + className);
                test(flags, className);
            } catch (Throwable t) {
                t.printStackTrace();
                errors++;
            }
        }

        if (errors == 0)
            System.out.println(count + " tests passed");
        else
            throw new Exception(errors + "/" + count + " tests failed");
    }

    void test(String[] flags, String className) throws Exception {
        count++;
        reset();
        StringBuilder sb = new StringBuilder();
        sb.append("module M { ");
        if (className != null) {
            sb.append("class ");
            for (String f: flags)
                sb.append(f + " ");
            sb.append(className);
            sb.append("; ");
        }
        sb.append("}");
        String moduleInfoBody = sb.toString();

        String classPath; // path name of the class
        String classBody;
        int dot = className.lastIndexOf('.');
        if (dot == -1) {
            classBody = "public class " + className + " { }";
            classPath = className + ".java";
        }
        else {
            classBody = "package " + className.substring(0, dot) + "; public class " + className.substring(dot + 1) + " { }";
            classPath = className.substring(dot + 1) + ".java";
        }

        List<File> files = new ArrayList<File>();
        addFile(files, createFile(classPath, classBody));
        addFile(files, createFile("module-info.java", moduleInfoBody));
        compile(files);
        checkModuleClassAttribute("module-info.class", flags, className.replace('.', '/'));
    }

    void checkModuleClassAttribute(String file, String[] flags, String className) throws IOException {
        System.err.println("Checking " + file);
        try {
            ClassFile cf = ClassFile.read(new File(classesDir, file));
            ModuleClass_attribute attr = (ModuleClass_attribute) cf.getAttribute(Attribute.ModuleClass);
            if (attr == null) {
                if (className != null)
                    error("ModuleClass attribute not found; expected " + Arrays.asList(flags) + " " + className);
            } else {
                if (className == null) {
                    error("Unexpected module attribute found: " + attr);
                } else {
                    ConstantPool cp = cf.constant_pool;
                    checkEqual("class name", className, attr.getClassName(cp));
                    checkEqual("class flags", flags, attr.getClassAttributes(cp));
                }
            }
        } catch (ConstantPoolException e) {
            error("Error accessing constant pool " + file + ": " + e);
            e.printStackTrace();
        } catch (IOException e) {
            error("Error reading " + file + ": " + e);
        }
    }

    <T> void checkEqual(String tag, T expect, T found) {
        if (expect == null ? found == null : expect.equals(found))
            return;
        error(tag + " mismatch", "expected " + expect, "found: " + found);
    }

    <T> void checkEqual(String tag, T[] expect, T[] found) {
        if (expect == null ? found == null : Arrays.equals(expect, found))
            return;
        error(tag + " mismatch", "expected " + Arrays.asList(expect), "found: " + Arrays.asList(found));
    }

    /**
     * Compile a list of files.
     */
    void compile(List<File> files) {
        List<String> options = new ArrayList<String>();
        options.addAll(Arrays.asList("-source", "7", "-doe", "-d", classesDir.getPath()));
        for (File f: files)
            options.add(f.getPath());

        String[] opts = options.toArray(new String[options.size()]);
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        int rc = com.sun.tools.javac.Main.compile(opts, pw);
        pw.close();

        String out = sw.toString();
        if (out.trim().length() > 0)
            System.err.println(out);
        if (rc != 0)
            throw new Error("compilation failed: rc=" + rc);
    }

    /**
     * Add a file to a list if the file is not null.
     */
    void addFile(List<File> files, File file) {
        if (file != null)
            files.add(file);
    }


    /**
     * Create a test file with given content if the content is not null.
     */
    File createFile(String path, String body) throws IOException {
        if (body == null)
            return null;
        File file = new File(srcDir, path);
        file.getAbsoluteFile().getParentFile().mkdirs();
        FileWriter out = new FileWriter(file);
        out.write(body);
        out.close();
        return file;
    }

    /**
     * Set up empty src and classes directories for a test.
     */
    void reset() {
        resetDir(srcDir);
        resetDir(classesDir);
    }

    /**
     * Set up an empty directory.
     */
    void resetDir(File dir) {
        if (dir.exists())
            deleteAll(dir);
        dir.mkdirs();
    }

    /**
     * Delete a file or a directory (including all its contents).
     */
    boolean deleteAll(File file) {
        if (file.isDirectory()) {
            for (File f: file.listFiles())
                deleteAll(f);
        }
        return file.delete();
    }

    /**
     * Report an error.
     */
    void error(String msg, String... more) {
        System.err.println("error: " + msg);
        for (String s: more)
            System.err.println(s);
        errors++;
    }

    int count;
    int errors;
    File srcDir = new File("tmp", "src"); // use "tmp" to help avoid accidents
    File classesDir = new File("tmp", "classes");
}
