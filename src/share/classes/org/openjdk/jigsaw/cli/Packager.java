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

package org.openjdk.jigsaw.cli;

import java.lang.module.*;
import java.io.*;
import java.security.SignatureException;
import java.util.*;

import static java.lang.System.out;
import static java.lang.System.err;

import org.openjdk.jigsaw.*;
import org.openjdk.jigsaw.SimpleLibrary.StorageOption;
import org.openjdk.internal.joptsimple.*;

/* Interface:

jpkg [-v] [-L <library>] [-r <resource-dir>] [-i include-dir] \
     [-m <module_dir>] [-d <output_dir>] [-c <command>] [-n <name>] \
     [-e <e-mail@address>] [-s <short description>] [-l <long description>] \
     [-x <extra metadata>] [deb|jmod] <module_name>*

  -v           : verbose output
  -L           : library the modules are installed to
  -i           : directory with files to include as part of the package
  -m           : directory with modules to package
  -d           : destination directory to put the package in
  -c           : command name for launcher invoking main class
  -n           : maintainer name
  -e           : maintainer e-mail address
  -s           : short description
  -l           : long description
  -x           : additional metadata - for Debian packages, a file whose
                 contents gets appended to DEBIAN/control
  --fast       : use fastest, rather then best compression
*/

public class Packager {

    private static JigsawModuleSystem jms
        = JigsawModuleSystem.instance();

    private static boolean jigsawDevMode
        = System.getenv("JIGSAW_DEV_MODE") != null;

    /** Temp dir for modules to be pre-installed into */
    private static File tmp_dst;

    /** Directory where the classes to package are stored on disk. */
    private File classes = new File(System.getProperty("user.dir"));

    /** Default destination dir is current directory */
    private File destination = new File(System.getProperty("user.dir"));

    /** The default location to install modules to */
    private File library = new File("/usr/lib/java/modules");

    /** The JRE to use in control scripts */
    private File javaHome = new File(System.getProperty("java.home"));

    private boolean verbose;

    private boolean fast;

    /** Command launcher for main class */
    private String bincmd;

    /** Directory with optional files to include in package */
    private File includes;

    /** Name of the maintainer or creator of the package */
    private String maintainer_name;

    /** Default name of the maintainer or creator of the package */
    private String default_maintainer_name = System.getProperty("user.name");

    /** E-mail address of the maintainer or creator of the package */
    private String maintainer_email;

    /** Default E-mail address of the maintainer or creator of the package */
    private String default_maintainer_email = "<generated@by.jpkg>";

    /** Short description of the package */
    private String short_description;

    /** Default short description of the package */
    private static String default_short_description = "Generated by jpkg";

    /** Long description of the package */
    private String long_description;

    /** Default long description of the package */
    private static String default_long_description
        = " This package was automatically generated from the corresponding Jigsaw module.\n"
        + " Information on Jigsaw is available at http://openjdk.java.net/projects/jigsaw.";

    /** Packaging-system dependent additional metadata */
    private File extra_metadata;

    private File natlibs;
    private File natcmds;
    private File config_dir;

    // Installed size
    private Integer installedSize = null;

    // Platform boot module
    private static final String BOOT_MODULE = "jdk.base";

    private static void createTempWorkDir()
        throws Command.Exception
    {
        try {
            tmp_dst = File.createTempFile("jigsaw",null);
            Files.delete(tmp_dst);
            Files.mkdirs(tmp_dst, "jigsaw temp directory");
        }
        catch (IOException x) {
            throw new Command.Exception(x);
        }
    }


    class Jmod extends Command<SimpleLibrary> {
        private String getModuleVersion(String modulename)
            throws Command.Exception
        {
            Manifest mf = Manifest.create(modulename, classes);
            ModuleInfo info = getModuleInfo(mf);
            return info.id().version().toString();
        }

        protected void go(SimpleLibrary lib)
            throws Command.Exception
        {
            while (hasArg()) {
                File outputfile = null;
                try {
                    String modulename = takeArg();
                    String version = getModuleVersion(modulename);
                    String outputfilename = modulename + '@'+ version + ".jmod";
                    if (verbose)
                        System.out.println("Creating module file "
                                           + outputfilename + " for "
                                           + modulename);
                    outputfile = new File(destination, outputfilename);
                    ModuleFileWriter writer
                        = new ModuleFileWriter(outputfile, (fast || jigsawDevMode));
                    writer.writeModule(classes, natlibs, natcmds, config_dir);
                }
                catch (IOException x) {
                    if (outputfile != null && !outputfile.delete()) {
                        Throwable t
                            = new IOException(outputfile +
                                              ": Cannot delete").initCause(x);
                        throw new Command.Exception((IOException)t);
                    }
                    throw new Command.Exception(x);
                }
            }
            finishArgs();
        }
    }

    static private ModuleInfo getModuleInfo(Manifest mf)
        throws Command.Exception
    {
        try {
            final String MINFO_CLASS = "module-info.class";
            File classdir = mf.classes().get(0);
            File mif = new File(classdir , MINFO_CLASS);
            if (!mif.exists())
                mif = new File(classdir,
                               mf.module() + File.separator + MINFO_CLASS);
            byte[] bs = Files.load(mif);
            return jms.parseModuleInfo(bs);
        } catch (IOException x) {
            throw new Command.Exception(x);
        }
    }

    class Deb extends Command<SimpleLibrary> {
        private File tmp_module_dst;
        private File tmp_metadata_dst;

        private void createMetaDataDir()
            throws Command.Exception
        {
            tmp_metadata_dst = new File(tmp_dst, "DEBIAN");
            if (!tmp_metadata_dst.mkdirs())
                throw new Command.Exception("Couldn't create meta data directory "
                                            + tmp_metadata_dst);
        }

        private void createModuleLibraryWorkDir()
            throws Command.Exception
        {
            tmp_module_dst = new File(tmp_dst, library.toString());

            if (!tmp_module_dst.mkdirs())
                throw new Command.Exception("Couldn't create module destination directory "
                                            + tmp_module_dst);

            // Delete the modules dir to make SimpleLibrary happy,
            // it wants to create the jigsaw metadata and the directory
            // along with it.
            if (!tmp_module_dst.delete())
                throw new Command.Exception("Can't delete " + tmp_module_dst);
        }

        private void preinstallModule(Manifest manifest)
            throws Command.Exception
        {
            try {

                createModuleLibraryWorkDir();
                Set<StorageOption> opts = Collections.emptySet();
                if (BOOT_MODULE.equals(manifest.module())) {
                    // Create a module library to the boot module package
                    SimpleLibrary.create(tmp_module_dst, opts).installFromManifests(Collections.singleton(manifest));
                } else {
                    // We need to create a throwaway SimpleLibrary to work with it,
                    // As there is no static preInstall method
                    File scratchlib_dst = new File(tmp_dst, "scratchlib");
                    SimpleLibrary.create(scratchlib_dst, opts).preInstall(manifest, tmp_module_dst);
                    Files.deleteTree(scratchlib_dst);
                }
            } catch (IOException | ConfigurationException x) {
                throw new Command.Exception(x);
            }
        }

        private String translateVersion(String v) {
            // Debian version format: [epoch:]upstream_version[-debian_revision]
            // upstream_version may contain only alphanumerics and '.', '+', '-', '~'
            // There is no epoch, ':' not allowed.
            //
            if (!v.matches("[A-Za-z0-9\\+-~\\.]+"))
                throw new AssertionError("Invalid debian version format: " + v);
            return v;
        }

        private String computeDependencies(ModuleInfo info)
        {
            StringBuilder deps = new StringBuilder();

            for (ViewDependence d : info.requiresModules()) {
                if (d.modifiers().contains(ViewDependence.Modifier.OPTIONAL))
                    continue; // skip optional dependency

                deps.append(", ")
                    .append(d.query().name())
                    .append(' ')
                    .append(d.query().versionQuery() != null ?
                            "(" + translateVersion(d.query().versionQuery().toString()) + ")" :
                            "");
            }

            return deps.length() > 0 ?
                deps.substring(2) :
                "";
        }

        private String computeProvides(ModuleInfo info)
        {
            StringBuilder deps = new StringBuilder();

            for (ModuleId id : info.defaultView().aliases())
                deps.append(", ")
                    .append(id.name());

            return deps.length() > 0 ?
                deps.substring(2) :
                "";
        }

        /** Long descriptions in Debian control files must start the line with a space. */
        private String formatDescription(String description)
        {
            return " " + description.replace("\n", "\n ");
        }

        /**
         * The ModuleInfo metadata gets translated into the package in the following way:
         *
         * package name is module's name
         * package version is module's version
         * package dependecies are module's required dependencies
         *
         * @param manifest The module's manifest
         */
        private void writeMetaData(Manifest manifest)
            throws Command.Exception
        {
            boolean bootmodule = BOOT_MODULE.equals(manifest.module());
            createMetaDataDir();
            ModuleInfo info = getModuleInfo(manifest);

            // Create the control file, and fill in dependency and provides info
            try (PrintStream control
                     = new PrintStream(new File(tmp_metadata_dst, "control"))) {
                control.format("Package: %s%n"
                               + "Version: %s%n"
                               + "Section: misc%n"
                               + "Priority: optional%n"
                               + "Architecture: "
                               + System.getProperty("os.arch") + "%n",
                               info.id().name(),
                               translateVersion(info.id().version().toString()));

                // If either maintainer name or e-mail is declared as parameter, use it
                if (null != maintainer_name || null != maintainer_email) {
                    control.format("Maintainer: %s %s%n",
                                   maintainer_name == null? default_maintainer_name : maintainer_name,
                                   maintainer_email == null? default_maintainer_email : maintainer_email);
                }
                // Otherwise, if there is no extra metadata, use defaults
                else if (null == extra_metadata) {
                    control.format("Maintainer: %s %s%n",
                                   default_maintainer_name,
                                   default_maintainer_email);
                }

                // If either short or long description is declared as parameter, use it
                if (null != short_description || null != long_description) {
                    control.format("Description: %s%n"
                                   + "%s%n",
                                   short_description == null? default_short_description : short_description,
                                   long_description == null? default_long_description : formatDescription(long_description));
                }
                // Otherwise, if there is no extra metadata, use defaults
                else if (null == extra_metadata) {
                    control.format("Description: %s%n"
                                   + "%s%n",
                                   default_short_description,
                                   default_long_description);
                }

                if (!bootmodule && !info.requiresModules().isEmpty())
                    control.format("Depends: %s\n", computeDependencies(info));
                if (!info.defaultView().aliases().isEmpty())
                    control.format("Provides: %s\n", computeProvides(info));
                if (null != extra_metadata)
                    control.format("%s\n", new String(Files.load(extra_metadata)));
                if (installedSize != null)
                    control.format("Installed-Size: %d\n", installedSize);

                // All jpkg generated packages (except boot) depend on a boot
                // package being installed at time of installation to be able to run jmod.
                if (!bootmodule)
                    control.format("Pre-Depends: %s\n", BOOT_MODULE);


                // Generate the launcher script, if a main class exists
                if (!bootmodule && info.defaultView().mainClass() != null) {
                    // If no command name is given, use module name
                    if (null == bincmd)
                        bincmd = info.id().name();

                    String BINDIR = "/usr/bin";
                    File bin = new File(tmp_dst + BINDIR);
                    if (!bin.mkdirs())
                        throw new IOException("Couldn't create " + tmp_dst + BINDIR);

                    File cmd = new File(bin, bincmd);
                    try (PrintStream launcher = new PrintStream(cmd)) {
                        String java_launcher = System.getProperty("java.home")
                                               + "/bin/java";
                        if (! (new File(java_launcher)).exists())
                            throw new IOException("Couldn't find java launcher "
                                                  + "at " + java_launcher);

                        launcher.format("#!/bin/sh\n" +
                                        "set -e\n" +
                                        "exec %s -ea -L %s -m %s \"$@\"\n",
                                        java_launcher, library,
                                        info.id().name());
                    }
                    cmd.setExecutable(true, false);
                }

                String installedJMod = javaHome.getPath() + "/bin/jmod";

                // Before a package is installed,
                //   check if the jigsaw module library needs to be created first
                if (!bootmodule) {
                    File preinst = new File(tmp_metadata_dst, "preinst");
                    try (PrintStream pis = new PrintStream(preinst)) {
                        pis.format("#!/bin/sh\n" +
                               "set -e\n" +
                               "if [ ! -f %1$s/%%jigsaw-library ]\n" +
                               "then\n" +
                               "  %2$s  -L %1$s create\n" +
                               "fi\n",
                               library, installedJMod);
                    }
                    preinst.setExecutable(true, false);

                    // After a package is installed,
                    //  reconfigure the jigsaw module library for the module
                    File postinst = new File(tmp_metadata_dst, "postinst");
                    try (PrintStream pis = new PrintStream(postinst)) {
                        pis.format("#!/bin/sh\n" +
                               "set -e\n" +
                               "if [ -f %s/%s.pack ] ; then\n" +
                               " for i in %s/*.pack ; do\n" +
                               "   %s/bin/unpack200 -r $i %s/tmp.jar\n" +
                               "   unzip -o -q -d / %s/tmp.jar\n" +
                               "   rm %s/tmp.jar\n" +
                               " done\n" +
                               "fi\n" +
                               "%s -L %s config %s\n",
                               library, manifest.module(),
                               library,
                               javaHome, library,
                               library,
                               library,
                               installedJMod, library, info.id());
                    }
                    postinst.setExecutable(true, false);
                }

                // Before a package is removed,
                //  remove the generated jigsaw module configuration
                File prerm = new File(tmp_metadata_dst, "prerm");
                try (PrintStream pis = new PrintStream(prerm)) {
                    pis.format("#!/bin/sh\n" +
                           "set -e\n" +
                           // Delete unpacked class files.
                           "if [ -e %1$s/%2$s/%3$s ]\n" +
                           "then\n" +
                           "  rm -rf %1$s/%2$s/%3$s\n" +
                           "fi\n" +
                           // Delete module library directory if it's empty.
                           "find %1$s/%2$s/ -maxdepth 0 -type d -empty -delete\n",
                           library, info.id().name(), info.id().version());
                }
                prerm.setExecutable(true, false);
            } catch (IOException x) {
                throw new Command.Exception(x);
            }
        }

        private void buildPackage()
            throws Command.Exception
        {
            String dashz = "-z" + ((fast || jigsawDevMode) ? 1 : 9);
            try {
                Process build
                    = (new ProcessBuilder("fakeroot", "dpkg-deb", dashz, "-Zlzma", "--build",
                                          tmp_dst.toString(), destination.toString())).start();

                try (BufferedReader br = new BufferedReader(
                         new InputStreamReader(build.getErrorStream()))) {

                    if (0 != build.waitFor())
                        throw new Command.Exception("Failed to create package "
                                                    + br.readLine());
                }
            } catch (IOException | InterruptedException x) {
                throw new Command.Exception(x);
            }
        }

        private void cleanup()
            throws Command.Exception
        {
            try {
                Files.deleteTree(tmp_dst);
            } catch (IOException x) {
                throw new Command.Exception(x);
            }
        }

        private File getPackFile(Manifest manifest)
        {
            return new File(tmp_module_dst, manifest.module() + ".pack");
        }

        private void packModule(Manifest manifest)
            throws Command.Exception
        {
            // Can't pack the boot module since unpack200 tool may not exist
            if (BOOT_MODULE.equals(manifest.module()))
                return;

            try {
                File tmp_jar = new File(tmp_dst, "module.jar");

                // Create temporary jar file with module classes and ressources
                // Store entries, as we'll just need it for pack200 to read from
                // and then delete it. No point in wasting time.
                Process jar
                    = (new ProcessBuilder("jar", "c0f",  tmp_jar.toString(),
                                          "-C", tmp_dst.toString(),
                                          library.toString())).start();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(jar.getErrorStream()))) {
                    if (0 != jar.waitFor())
                        throw new Command.Exception("Failed to jar module "
                                                    + br.readLine());
                }

                // Remove redundant META-INF directory from jar file,
                // so that it doesn't pollute the filesystem hierarchy
                // when we unpack the files again.
                Process zip
                    = (new ProcessBuilder("zip",  tmp_jar.toString(),
                                          "-d", "META-INF/*")).start();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(zip.getErrorStream()))) {
                    if (0 != zip.waitFor())
                        throw new Command.Exception("Failed to remove META-INF "
                                                    + "directory from jar "
                                                    + "module "
                                                    + br.readLine());
                }
                // Compress the jar file with pack200.
                String dashE = "-E" + ((fast || jigsawDevMode) ? "0" : "9");
                Process pack200
                    = (new ProcessBuilder("pack200", dashE, "-S-1", "--no-gzip",
                                          getPackFile(manifest).toString(),
                                          tmp_jar.toString())).start();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(pack200.getErrorStream()))) {
                    if (0 != pack200.waitFor())
                        throw new Command.Exception("Failed to pack200 module "
                                                    + br.readLine());
                }

                if (! tmp_jar.delete())
                    throw new Command.Exception("Failed to delete temporary file " + tmp_jar);
                Files.deleteTree(new File(tmp_module_dst, manifest.module().toString()));
            } catch (IOException | InterruptedException x) {
                throw new Command.Exception(x);
            }
        }

        protected void go(SimpleLibrary lib)
            throws Command.Exception
        {
            List<Manifest> mfs = new ArrayList<>();
            while (hasArg()) {
                mfs.add(Manifest.create(takeArg(), classes));
            }
            finishArgs();

            for (Manifest manifest : mfs) {

                if (verbose)
                    System.out.println("Creating binary Debian package for " + manifest.module());

                createTempWorkDir();
                if (null != includes) {
                    try {
                        Files.copyTree(includes, tmp_dst);
                    } catch (IOException x) {
                        throw new Command.Exception(x);
                    }
                }
                preinstallModule(manifest);
                packModule(manifest);
                writeMetaData(manifest);
                buildPackage();
                cleanup();
            }
        }
    }

    private static Map<String,Class<? extends Command<SimpleLibrary>>> commands
        = new HashMap<>();

    static {
        commands.put("deb", Deb.class);
        commands.put("jmod", Jmod.class);
    }

    private OptionParser parser;

    private static OptionSpec<File> resourcePath; // ##

    private void usage() {
        out.format("%n");
        out.format("usage: jpkg [-v] [-L <library>] [-r <resource-dir>] [-i <include-dir>] [-m <module-dir>] [-d <output-dir>]  [-c <command>] [-n <name>] [-e <e-mail@address>] [-s <short description>] [-l <long description>] [-x <extra metadata>] [deb|jmod] <module-name>%n");
        out.format("%n");
        try {
            parser.printHelpOn(out);
        } catch (IOException x) {
            throw new AssertionError(x);
        }
        out.format("%n");
    }

    public static void run(String[] args)
        throws OptionException, Command.Exception {

        new Packager().exec(args);
    }

    private void exec(String[] args)
        throws OptionException, Command.Exception {

        parser = new OptionParser();

        OptionSpec<File> libPath
            = (parser.acceptsAll(Arrays.asList("L", "library"),
                                 "Module-library location"
                                 + " (default $JAVA_MODULES)")
               .withRequiredArg()
               .describedAs("path")
               .ofType(File.class));

        OptionSpec<File> launcherPath
            = (parser.acceptsAll(Arrays.asList("c", "command"),
                                 "Launcher command name")
               .withRequiredArg()
               .describedAs("path")
               .ofType(File.class));

        parser.acceptsAll(Arrays.asList("v", "verbose"),
                          "Enable verbose output");
        parser.acceptsAll(Arrays.asList("h", "?", "help"),
                          "Show this help message");
        OptionSpec<File> destinationPath
            = (parser.acceptsAll(Arrays.asList("d", "dest-dir"),
                          "Destination directory for packages")
               .withRequiredArg()
               .describedAs("path")
               .ofType(File.class));

        OptionSpec<File> modulePath
            = (parser.acceptsAll(Arrays.asList("m", "module-dir"),
                          "Source directory for modules")
               .withRequiredArg()
               .describedAs("path")
               .ofType(File.class));

        OptionSpec<File> includePath
            = (parser.acceptsAll(Arrays.asList("i", "include"),
                                 "Directory of files to be included")
               .withRequiredArg()
               .describedAs("path")
               .ofType(File.class));

        OptionSpec<String> maintainerName
            = (parser.acceptsAll(Arrays.asList("n", "name"),
                                 "Package maintainer's name")
               .withRequiredArg()
               .describedAs("name")
               .ofType(String.class));

        OptionSpec<String> maintainerEmail
            = (parser.acceptsAll(Arrays.asList("e", "email"),
                                 "Package maintainer's e-mail address")
               .withRequiredArg()
               .describedAs("e-mail@address")
               .ofType(String.class));

        OptionSpec<String> shortDescription
            = (parser.acceptsAll(Arrays.asList("s", "short"),
                                 "Short description of the package")
               .withRequiredArg()
               .describedAs("description")
               .ofType(String.class));

        OptionSpec<String> longDescription
            = (parser.acceptsAll(Arrays.asList("l", "long"),
                                 "Long description of the package")
               .withRequiredArg()
               .describedAs("description")
               .ofType(String.class));

        parser.acceptsAll(Arrays.asList("fast"),
                          "Use fastest compression");

        OptionSpec<Integer> isize
            = (parser.acceptsAll(Arrays.asList("installed-size"),
                                 "Installed size in kilobytes")
               .withRequiredArg()
               .describedAs("size")
               .ofType(Integer.class));

        OptionSpec<File> javaHomePath
            = (parser.acceptsAll(Arrays.asList("java-home"),
                                 "Alternate $JAVA_HOME location")
               .withRequiredArg()
               .describedAs("dir")
               .ofType(File.class));

        OptionSpec<File> extraMetadata
            = (parser.acceptsAll(Arrays.asList("x", "extra"),
                                 "File or directory with additional metadata,"
                                 + " depending on the packaging system")
               .withRequiredArg()
               .describedAs("dir")
               .ofType(File.class));

        OptionSpec<File> nativeLibs
            = (parser.accepts("natlib", "Directory with native libs")
               .withRequiredArg()
               .describedAs("dir")
               .ofType(File.class));

        OptionSpec<File> nativeCmds
            = (parser.accepts("natcmd", "Directory with native launchers")
               .withRequiredArg()
               .describedAs("dir")
               .ofType(File.class));

        OptionSpec<File> config
            = (parser.accepts("config", "Directory with configuration")
               .withRequiredArg()
               .describedAs("dir")
               .ofType(File.class));

        if (args.length == 0) {
            usage();
            return;
        }

        OptionSet opts = parser.parse(args);
        if (opts.has("h")) {
            usage();
            return;
        }
        if (opts.has("v"))
            verbose = true;
        if (opts.has("fast"))
            fast = true;
        List<String> words = opts.nonOptionArguments();
        if (words.isEmpty()) {
            usage();
            return;
        }
        String verb = words.get(0);
        Class<? extends Command<SimpleLibrary>> cmd = commands.get(verb);
        if (cmd == null)
            throw new Command.Exception("%s: unknown command", verb);
        if (opts.has(launcherPath))
            bincmd = opts.valueOf(launcherPath).toString();
        if (opts.has(destinationPath))
            destination = opts.valueOf(destinationPath);
        if (opts.has(modulePath)) {
            classes = opts.valueOf(modulePath);
            checkPathArgument(classes, "Module");
        }
        if (opts.has(libPath)) {
            library = opts.valueOf(libPath);
        } else {
            String jm = System.getenv("JAVA_MODULES");
            if (jm != null)
                library = new File(jm);
        }
        if (opts.has(includePath))
            includes = opts.valueOf(includePath);
        if (opts.has(javaHomePath))
            javaHome = opts.valueOf(javaHomePath);
        if (opts.has(maintainerName))
            maintainer_name = opts.valueOf(maintainerName);
        if (opts.has(maintainerEmail)) {
            maintainer_email = opts.valueOf(maintainerEmail);
            // Add missing e-mail quotes if necessary
            maintainer_email
                = (maintainer_email.startsWith("<") ? "" : "<")
                + maintainer_email
                + (maintainer_email.endsWith(">") ? "" : ">") ;
        }
        if (opts.has(shortDescription))
            short_description = opts.valueOf(shortDescription);
        if (opts.has(longDescription))
            long_description = opts.valueOf(longDescription);
        if (opts.has(extraMetadata))
            extra_metadata = opts.valueOf(extraMetadata);
        if (opts.has(nativeLibs)) {
            natlibs = opts.valueOf(nativeLibs);
            checkPathArgument(natlibs, "Native library");
        }
        if (opts.has(nativeCmds)) {
            natcmds = opts.valueOf(nativeCmds);
            checkPathArgument(natcmds, "Native command");
        }
        if (opts.has(config)) {
            config_dir = opts.valueOf(config);
            checkPathArgument(config_dir, "Config");
        }
        if (opts.has(isize))
            installedSize = opts.valueOf(isize);

        if (cmd == Deb.class)
            (new Deb()).run(null, opts);
        else if (cmd == Jmod.class)
            (new Jmod()).run(null, opts);
    }

    /**
     * Helper method to check if a path exists before using it further.
     *
     * @param path to check
     * @param type of path being checked
     *
     * @throws Command.Exception if path doesn't exist
     */
    private static final void checkIfPathExists(File path, String type)
        throws Command.Exception {

        if (!path.exists())
            throw new Command.Exception("%s path doesn't exist: %s",
                                        type, path);
    }

    /**
     * Helper method to check if a path is readable before using it further.
     *
     * @param path to check
     * @param type of path being checked
     *
     * @throws Command.Exception if path isn't readable
     */
    private static final void checkIfPathIsReadable(File path, String type)
        throws Command.Exception {

        if (!path.canRead())
            throw new Command.Exception("%s path isn't readable: %s",
                                        type, path);
    }

    /**
     * Helper method to check if a path is a directory before using it further.
     *
     * @param path to check
     * @param type of path being checked
     *
     * @throws Command.Exception if path is not a directory
     */
    private static final void checkIfPathIsDirectory(File path, String type)
        throws Command.Exception {

        if (!path.isDirectory())
            throw new Command.Exception("%s path is not a directory: %s",
                                        type, path);
    }

    /**
     * Helper method to check if a path argument is valid.
     *
     * @param path to check
     * @param type of path being checked
     *
     * @throws Command.Exception if path is not a directory
     */
    private static final void checkPathArgument(File path, String type)
        throws Command.Exception {

        checkIfPathExists(path, type);
        checkIfPathIsReadable(path, type);
        checkIfPathIsDirectory(path, type);
    }

    private Packager() { }

    public static void main(String[] args) throws Exception {
        try {
            run(args);
        } catch (OptionException | Command.Exception x) {
            err.println(x.getMessage());
            System.exit(1);
        }
    }

}
