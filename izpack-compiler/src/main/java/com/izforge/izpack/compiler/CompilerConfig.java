/*
 * $Id$
 * IzPack - Copyright 2001-2008 Julien Ponge, All Rights Reserved.
 *
 * http://izpack.org/
 * http://izpack.codehaus.org/
 *
 * Copyright 2001 Johannes Lehtinen
 * Copyright 2002 Paul Wilkinson
 * Copyright 2004 Gaganis Giorgos
 * Copyright 2007 Syed Khadeer / Hans Aikema
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.izforge.izpack.compiler;

import com.izforge.izpack.ExecutableFile;
import com.izforge.izpack.adaptator.IXMLElement;
import com.izforge.izpack.adaptator.IXMLParser;
import com.izforge.izpack.adaptator.IXMLWriter;
import com.izforge.izpack.adaptator.impl.XMLParser;
import com.izforge.izpack.adaptator.impl.XMLWriter;
import com.izforge.izpack.compiler.data.CompilerData;
import com.izforge.izpack.compiler.helper.CompilerHelper;
import com.izforge.izpack.data.*;
import com.izforge.izpack.data.PanelAction.ActionStage;
import com.izforge.izpack.installer.DataValidator;
import com.izforge.izpack.rules.Condition;
import com.izforge.izpack.rules.RulesEngineImpl;
import com.izforge.izpack.util.Debug;
import com.izforge.izpack.util.OsConstraint;
import com.izforge.izpack.util.substitutor.SubstitutionType;
import com.izforge.izpack.util.substitutor.VariableSubstitutor;
import com.izforge.izpack.util.substitutor.VariableSubstitutorImpl;
import org.apache.tools.ant.DirectoryScanner;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.JarInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;


/**
 * A parser for the installer xml configuration. This parses a document conforming to the
 * installation.dtd and populates a Compiler instance to perform the install compilation.
 *
 * @author Scott Stark
 * @version $Revision$
 */
public class CompilerConfig extends Thread {

    /**
     * Constant for checking attributes.
     */
    private static boolean YES = true;

    /**
     * Constant for checking attributes.
     */
    private static boolean NO = false;


    /**
     * The xml install file
     */
    private String filename;

    /**
     * The xml install configuration text
     */
    private String installText;

    /**
     * The base directory.
     */
    protected String basedir;

    /**
     * The installer packager compiler
     */
    private Compiler compiler;

    /**
     * Installer data
     */
    private CompilerData compilerData;

    /**
     * Compiler helper
     */
    private CompilerHelper compilerHelper = new CompilerHelper();

    /**
     * List of CompilerListeners which should be called at packaging
     */
    protected List<CompilerListener> compilerListeners = new ArrayList<CompilerListener>();

    /**
     * A list of packsLang-files that were defined by the user in the resource-section The key of
     * this map is an packsLang-file identifier, e.g. <code>packsLang.xml_eng</code>, the values
     * are lists of {@link URL} pointing to the concrete packsLang-files.
     *
     * @see #mergePacksLangFiles()
     */
    private HashMap<String, List<URL>> packsLangUrlMap = new HashMap<String, List<URL>>();
    private String unpackerClassname = "com.izforge.izpack.installer.unpacker.Unpacker";
    private String packagerClassname = "com.izforge.izpack.compiler.Packager";


    /**
     * Constructor
     *
     * @param compilerData Object containing all informations found in command line
     */
    public CompilerConfig(CompilerData compilerData) {
        this.compilerData = compilerData;
    }

    /**
     * Add a name value pair to the project property set. It is <i>not</i> replaced it is already
     * in the set of properties.
     *
     * @param name  the name of the property
     * @param value the value to set
     * @return true if the property was not already set
     */
    public boolean addProperty(String name, String value) {
        return compiler.addProperty(name, value);
    }

    /**
     * Access the install compiler
     *
     * @return the install compiler
     */
    public Compiler getCompiler() {
        return compiler;
    }

    /**
     * Retrieves the packager listener
     */
    public PackagerListener getPackagerListener() {
        return compiler.getPackagerListener();
    }

    /**
     * The run() method.
     */
    public void run() {
        try {
            executeCompiler();
        }
        catch (CompilerException ce) {
            System.out.println(ce.getMessage() + "\n");
        }
        catch (Exception e) {
            if (Debug.stackTracing()) {
                e.printStackTrace();
            } else {
                System.out.println("ERROR: " + e.getMessage());
            }
        }
    }

    /**
     * Compiles the installation.
     *
     * @throws Exception Description of the Exception
     */
    public void executeCompiler() throws Exception {
        // normalize and test: TODO: may allow failure if we require write
        // access
        File base = new File(basedir).getAbsoluteFile();
        if (!base.canRead() || !base.isDirectory()) {
            throw new CompilerException(
                    "Invalid base directory: " + base);
        }

        // add izpack built in property
        compiler.setProperty("basedir", base.toString());

        // We get the XML data tree
        IXMLElement data = getXMLTree();
        // loads the specified packager
        loadPackagingInformation(data);

        // Listeners to various events
        addCustomListeners(data);

        // Read the properties and perform replacement on the rest of the tree
        substituteProperties(data);

        // We add all the information
        addVariables(data);
        addDynamicVariables(data);
        addConditions(data);
        addInfo(data);
        addGUIPrefs(data);
        addLangpacks(data);
        addResources(data);
        addNativeLibraries(data);
        addJars(data);
        addPanels(data);
        addPacks(data);
        addInstallerRequirement(data);

        // merge multiple packlang.xml files
        mergePacksLangFiles();

        // We ask the packager to create the installer
        compiler.createInstaller();
    }

    private void addInstallerRequirement(IXMLElement data) throws CompilerException {
        notifyCompilerListener("addInstallerRequirement", CompilerListener.BEGIN, data);
        IXMLElement root = data.getFirstChildNamed("installerrequirements");
        List<InstallerRequirement> installerrequirements = new ArrayList<InstallerRequirement>();

        if (root != null) {
            Vector<IXMLElement> installerrequirementsels = root
                    .getChildrenNamed("installerrequirement");
            for (IXMLElement installerrequirement : installerrequirementsels) {
                InstallerRequirement basicInstallerCondition = new InstallerRequirement();
                String condition = installerrequirement.getAttribute("condition");
                basicInstallerCondition.setCondition(condition);
                String message = installerrequirement.getAttribute("message");
                basicInstallerCondition.setMessage(message);
                installerrequirements.add(basicInstallerCondition);
            }
        }
        compiler.addInstallerRequirement(installerrequirements);
        notifyCompilerListener("addInstallerRequirement", CompilerListener.END, data);
    }

    private void loadPackagingInformation(IXMLElement data) throws CompilerException {
        notifyCompilerListener("loadPackager", CompilerListener.BEGIN, data);
        // Initialisation
        IXMLElement root = data.getFirstChildNamed("packaging");
        IXMLElement packager = null;
        if (root != null) {
            packager = root.getFirstChildNamed("packager");

            if (packager != null) {
                packagerClassname = requireAttribute(packager, "class");
            }

            IXMLElement unpacker = root.getFirstChildNamed("unpacker");

            if (unpacker != null) {
                unpackerClassname = requireAttribute(unpacker, "class");
            }
        }
        compiler.initPackager(packagerClassname);
        if (packager != null) {
            IXMLElement options = packager.getFirstChildNamed("options");
            if (options != null) {
                compiler.getPackager().addConfigurationInformation(options);
            }
        }
        compiler.addProperty("UNPACKER_CLASS", unpackerClassname);
        notifyCompilerListener("loadPackager", CompilerListener.END, data);
    }

    public boolean wasSuccessful() {
        return compiler.wasSuccessful();
    }

    /**
     * Returns the GUIPrefs.
     *
     * @param data The XML data.
     * @throws CompilerException Description of the Exception
     */
    protected void addGUIPrefs(IXMLElement data) throws CompilerException {
        notifyCompilerListener("addGUIPrefs", CompilerListener.BEGIN, data);
        // We get the IXMLElement & the attributes
        IXMLElement gp = data.getFirstChildNamed("guiprefs");
        GUIPrefs prefs = new GUIPrefs();
        if (gp != null) {
            prefs.resizable = requireYesNoAttribute(gp, "resizable");
            prefs.width = requireIntAttribute(gp, "width");
            prefs.height = requireIntAttribute(gp, "height");

            // Look and feel mappings
            for (IXMLElement lafNode : gp.getChildrenNamed("laf")) {
                String lafName = requireAttribute(lafNode, "name");
                requireChildNamed(lafNode, "os");

                for (IXMLElement osNode : lafNode.getChildrenNamed("os")) {
                    String osName = requireAttribute(osNode, "family");
                    prefs.lookAndFeelMapping.put(osName, lafName);
                }

                Map<String, String> params = new TreeMap<String, String>();
                for (IXMLElement parameterNode : lafNode.getChildrenNamed("param")) {
                    String name = requireAttribute(parameterNode, "name");
                    String value = requireAttribute(parameterNode, "value");
                    params.put(name, value);
                }
                prefs.lookAndFeelParams.put(lafName, params);
            }
            // Load modifier
            for (IXMLElement ixmlElement : gp.getChildrenNamed("modifier")) {
                String key = requireAttribute(ixmlElement, "key");
                String value = requireAttribute(ixmlElement, "value");
                prefs.modifier.put(key, value);

            }
            // make sure jar contents of each are available in installer
            // map is easier to read/modify than if tree
            HashMap<String, String> lafMap = new HashMap<String, String>();
            lafMap.put("liquid", "liquidlnf.jar");
            lafMap.put("kunststoff", "kunststoff.jar");
            lafMap.put("metouia", "metouia.jar");
            lafMap.put("looks", "looks.jar");
            lafMap.put("substance", "substance.jar");
            lafMap.put("nimbus", "nimbus.jar");

            // is this really what we want? a double loop? needed, since above,
            // it's
            // the /last/ lnf for an os which is used, so can't add during
            // initial
            // loop
            for (String s : prefs.lookAndFeelMapping.keySet()) {
                String lafName = prefs.lookAndFeelMapping.get(s);
                String lafJarName = lafMap.get(lafName);
                if (lafJarName == null) {
                    parseError(gp, "Unrecognized Look and Feel: " + lafName);
                }

                URL lafJarURL = findIzPackResource("lib/" + lafJarName, "Look and Feel Jar file",
                        gp);
                compiler.addJarContent(lafJarURL);
            }
        }
        compiler.setGUIPrefs(prefs);
        notifyCompilerListener("addGUIPrefs", CompilerListener.END, data);
    }

    /**
     * Add project specific external jar files to the installer.
     *
     * @param data The XML data.
     */
    protected void addJars(IXMLElement data) throws Exception {
        notifyCompilerListener("addJars", CompilerListener.BEGIN, data);
        for (IXMLElement ixmlElement : data.getChildrenNamed("jar")) {
            String src = requireAttribute(ixmlElement, "src");
            URL url = findProjectResource(src, "Jar file", ixmlElement);
            compiler.addJarContent(url);
            // Additionals for mark a jar file also used in the uninstaller.
            // The contained files will be copied from the installer into the
            // uninstaller if needed.
            // Therefore the contained files of the jar should be in the
            // installer also
            // they are used only from the uninstaller. This is the reason why
            // the stage
            // wiil be only observed for the uninstaller.
            String stage = ixmlElement.getAttribute("stage");
            if (stage != null
                    && ("both".equalsIgnoreCase(stage) || "uninstall".equalsIgnoreCase(stage))) {
                CustomData ca = new CustomData(null, getContainedFilePaths(url), null,
                        CustomData.UNINSTALLER_JAR);
                compiler.addCustomJar(ca, url);
            }
        }
        notifyCompilerListener("addJars", CompilerListener.END, data);
    }

    /**
     * Add native libraries to the installer.
     *
     * @param data The XML data.
     */
    protected void addNativeLibraries(IXMLElement data) throws Exception {
        boolean needAddOns = false;
        notifyCompilerListener("addNativeLibraries", CompilerListener.BEGIN, data);
        for (IXMLElement ixmlElement : data.getChildrenNamed("native")) {
            String type = requireAttribute(ixmlElement, "type");
            String name = requireAttribute(ixmlElement, "name");
            String path = ixmlElement.getAttribute("src");
            if (path == null) {
                path = "bin/native/" + type + "/" + name;
            }
            URL url = findIzPackResource(path, "Native Library", ixmlElement);
            compiler.addNativeLibrary(name, url);
            // Additionals for mark a native lib also used in the uninstaller
            // The lib will be copied from the installer into the uninstaller if
            // needed.
            // Therefore the lib should be in the installer also it is used only
            // from
            // the uninstaller. This is the reason why the stage wiil be only
            // observed
            // for the uninstaller.
            String stage = ixmlElement.getAttribute("stage");
            List<OsConstraint> constraints = OsConstraint.getOsList(ixmlElement);
            if (stage != null
                    && ("both".equalsIgnoreCase(stage) || "uninstall".equalsIgnoreCase(stage))) {
                ArrayList<String> al = new ArrayList<String>();
                al.add(name);
                CustomData cad = new CustomData(null, al, constraints, CustomData.UNINSTALLER_LIB);
                compiler.addNativeUninstallerLibrary(cad);
                needAddOns = true;
            }

        }
        if (needAddOns) {
            // Add the uninstaller extensions as a resource if specified
            IXMLElement root = requireChildNamed(data, "info");
            IXMLElement uninstallInfo = root.getFirstChildNamed("uninstaller");
            if (validateYesNoAttribute(uninstallInfo, "write", YES)) {
                URL url = findIzPackResource(compiler.getProperty("uninstaller-ext"), "Uninstaller extensions",
                        root);
                compiler.addResource("IzPack.uninstaller-ext", url);
            }

        }
        notifyCompilerListener("addNativeLibraries", CompilerListener.END, data);
    }

    /**
     * Add packs and their contents to the installer.
     *
     * @param data The XML data.
     */
    protected void addPacks(IXMLElement data) throws CompilerException {
        notifyCompilerListener("addPacks", CompilerListener.BEGIN, data);

        // the actual adding is delegated to addPacksSingle to enable recursive
        // parsing of refpack package definitions
        addPacksSingle(data);

        compiler.checkDependencies();
        compiler.checkExcludes();

        notifyCompilerListener("addPacks", CompilerListener.END, data);
    }

    /**
     * Add packs and their contents to the installer without checking the dependencies and includes.
     * <p/> Helper method to recursively add more packs from refpack XML packs definitions
     *
     * @param data The XML data
     * @throws CompilerException
     */
    private void addPacksSingle(IXMLElement data) throws CompilerException {
        notifyCompilerListener("addPacksSingle", CompilerListener.BEGIN, data);
        // Initialisation
        IXMLElement root = requireChildNamed(data, "packs");

        // at least one pack is required
        Vector<IXMLElement> packElements = root.getChildrenNamed("pack");
        Vector<IXMLElement> refPackElements = root.getChildrenNamed("refpack");
        Vector<IXMLElement> refPackSets = root.getChildrenNamed("refpackset");
        if (packElements.isEmpty() && refPackElements.isEmpty() && refPackSets.isEmpty()) {
            parseError(root, "<packs> requires a <pack>, <refpack> or <refpackset>");
        }

        File baseDir = new File(basedir);

        for (IXMLElement packElement : packElements) {

            // Trivial initialisations
            String name = requireAttribute(packElement, "name");
            String id = packElement.getAttribute("id");
            String packImgId = packElement.getAttribute("packImgId");

            boolean loose = "true".equalsIgnoreCase(packElement.getAttribute("loose", "false"));
            String description = requireChildNamed(packElement, "description").getContent();
            boolean required = requireYesNoAttribute(packElement, "required");
            String group = packElement.getAttribute("group");
            String installGroups = packElement.getAttribute("installGroups");
            String excludeGroup = packElement.getAttribute("excludeGroup");
            boolean uninstall = "yes".equalsIgnoreCase(packElement.getAttribute("uninstall", "yes"));
            String parent = packElement.getAttribute("parent");
            boolean hidden = "true".equalsIgnoreCase(packElement.getAttribute("hidden", "false"));

            String conditionid = packElement.getAttribute("condition");

            if (required && excludeGroup != null) {
                parseError(packElement, "Pack, which has excludeGroup can not be required.", new Exception(
                        "Pack, which has excludeGroup can not be required."));
            }

            PackInfo pack = new PackInfo(name, id, description, required, loose, excludeGroup,
                    uninstall);
            pack.setOsConstraints(OsConstraint.getOsList(packElement)); // TODO:
            pack.setParent(parent);
            pack.setCondition(conditionid);
            pack.setHidden(hidden);
            VariableSubstitutor varsubst = new VariableSubstitutorImpl(compiler.getVariables());

            // unverified
            // if the pack belongs to an excludeGroup it's not preselected by default
            if (excludeGroup == null) {
                pack.setPreselected(validateYesNoAttribute(packElement, "preselected", YES));
            } else {
                pack.setPreselected(validateYesNoAttribute(packElement, "preselected", NO));
            }

            // Set the pack group if specified
            if (group != null) {
                pack.setGroup(group);
            }
            // Set the pack install groups if specified
            if (installGroups != null) {
                StringTokenizer st = new StringTokenizer(installGroups, ",");
                while (st.hasMoreTokens()) {
                    String igroup = st.nextToken();
                    pack.addInstallGroup(igroup);
                }
            }

            // Set the packImgId if specified
            if (packImgId != null) {
                pack.setPackImgId(packImgId);
            }

            // We get the parsables list
            for (IXMLElement parsableNode : packElement.getChildrenNamed("parsable")) {
                String target = requireAttribute(parsableNode, "targetfile");
                SubstitutionType type = SubstitutionType.lookup(parsableNode.getAttribute("type", "plain"));
                String encoding = parsableNode.getAttribute("encoding", null);
                List<OsConstraint> osList = OsConstraint.getOsList(parsableNode); // TODO: unverified
                String condition = parsableNode.getAttribute("condition");
                ParsableFile parsable = new ParsableFile(target, type, encoding, osList);
                parsable.setCondition(condition);
                pack.addParsable(parsable);
            }

            // We get the executables list
            for (IXMLElement executableNode : packElement.getChildrenNamed("executable")) {
                ExecutableFile executable = new ExecutableFile();
                String val; // temp value
                String condition = executableNode.getAttribute("condition");
                executable.setCondition(condition);
                executable.path = requireAttribute(executableNode, "targetfile");

                // when to execute this executable
                val = executableNode.getAttribute("stage", "never");
                if ("postinstall".equalsIgnoreCase(val)) {
                    executable.executionStage = ExecutableFile.POSTINSTALL;
                } else if ("uninstall".equalsIgnoreCase(val)) {
                    executable.executionStage = ExecutableFile.UNINSTALL;
                }

                // type of this executable
                val = executableNode.getAttribute("type", "bin");
                if ("jar".equalsIgnoreCase(val)) {
                    executable.type = ExecutableFile.JAR;
                    executable.mainClass = executableNode.getAttribute("class"); // executable
                    // class
                }

                // what to do if execution fails
                val = executableNode.getAttribute("failure", "ask");
                if ("abort".equalsIgnoreCase(val)) {
                    executable.onFailure = ExecutableFile.ABORT;
                } else if ("warn".equalsIgnoreCase(val)) {
                    executable.onFailure = ExecutableFile.WARN;
                } else if ("ignore".equalsIgnoreCase(val)) {
                    executable.onFailure = ExecutableFile.IGNORE;
                }

                // whether to keep the executable after executing it
                val = executableNode.getAttribute("keep");
                executable.keepFile = "true".equalsIgnoreCase(val);

                // get arguments for this executable
                IXMLElement args = executableNode.getFirstChildNamed("args");
                if (null != args) {
                    for (IXMLElement ixmlElement : args.getChildrenNamed("arg")) {
                        executable.argList.add(requireAttribute(ixmlElement, "value"));
                    }
                }

                executable.osList = OsConstraint.getOsList(executableNode); // TODO:
                // unverified

                pack.addExecutable(executable);
            }

            // We get the files list
            for (IXMLElement fileNode : packElement.getChildrenNamed("file")) {
                String src = requireAttribute(fileNode, "src");
                String targetdir = requireAttribute(fileNode, "targetdir");
                List<OsConstraint> osList = OsConstraint.getOsList(fileNode); // TODO: unverified
                int override = getOverrideValue(fileNode);
                int blockable = getBlockableValue(fileNode, osList);
                Map additionals = getAdditionals(fileNode);
                boolean unpack = "true".equalsIgnoreCase(fileNode.getAttribute("unpack"));
                String condition = fileNode.getAttribute("condition");

                File file = new File(src);

                // if the path does not exist, maybe it contains variables
                if (!file.exists()) {
                    file = new File(varsubst.substitute(src, null));
                    // next existence check appears in pack.addFile
                }

                if (!file.isAbsolute()) {
                    file = new File(basedir, file.getPath());
                }

                try {
                    if (unpack) {
                        addArchiveContent(baseDir, file, targetdir, osList, override, blockable,
                                pack, additionals, condition);
                    } else {
                        addRecursively(baseDir, file, targetdir, osList, override, blockable,
                                pack, additionals, condition);
                    }
                }
                catch (Exception x) {
                    parseError(fileNode, x.getMessage(), x);
                }
            }

            // We get the singlefiles list
            for (IXMLElement singleFileNode : packElement.getChildrenNamed("singlefile")) {
                String src = requireAttribute(singleFileNode, "src");
                String target = requireAttribute(singleFileNode, "target");
                List<OsConstraint> osList = OsConstraint.getOsList(singleFileNode); // TODO: unverified
                int override = getOverrideValue(singleFileNode);
                int blockable = getBlockableValue(singleFileNode, osList);
                Map additionals = getAdditionals(singleFileNode);
                String condition = singleFileNode.getAttribute("condition");
                File file = new File(src);
                if (!file.isAbsolute()) {
                    file = new File(basedir, src);
                }

                // if the path does not exist, maybe it contains variables
                if (!file.exists()) {
                    file = new File(varsubst.substitute(file.getAbsolutePath(), null));
                    // next existance checking appears in pack.addFile
                }

                try {
                    pack.addFile(baseDir, file, target, osList, override, blockable, additionals, condition);
                }
                catch (FileNotFoundException x) {
                    parseError(singleFileNode, x.getMessage(), x);
                }
            }

            // We get the fileset list
            for (IXMLElement fileSetNode : packElement.getChildrenNamed("fileset")) {
                String dir_attr = requireAttribute(fileSetNode, "dir");

                File dir = new File(dir_attr);
                if (!dir.isAbsolute()) {
                    dir = new File(basedir, dir_attr);
                }
                if (!dir.isDirectory()) // also tests '.exists()'
                {
                    parseError(fileSetNode, "Invalid directory 'dir': " + dir_attr);
                }

                boolean casesensitive = validateYesNoAttribute(fileSetNode, "casesensitive", YES);
                boolean defexcludes = validateYesNoAttribute(fileSetNode, "defaultexcludes", YES);
                String targetdir = requireAttribute(fileSetNode, "targetdir");
                List<OsConstraint> osList = OsConstraint.getOsList(fileSetNode); // TODO: unverified
                int override = getOverrideValue(fileSetNode);
                int blockable = getBlockableValue(fileSetNode, osList);
                Map additionals = getAdditionals(fileSetNode);
                String condition = fileSetNode.getAttribute("condition");

                // get includes and excludes
                Vector<IXMLElement> xcludesList;
                String[] includes = null;
                xcludesList = fileSetNode.getChildrenNamed("include");
                if (!xcludesList.isEmpty()) {
                    includes = new String[xcludesList.size()];
                    for (int j = 0; j < xcludesList.size(); j++) {
                        IXMLElement xclude = xcludesList.get(j);
                        includes[j] = requireAttribute(xclude, "name");
                    }
                }
                String[] excludes = null;
                xcludesList = fileSetNode.getChildrenNamed("exclude");
                if (!xcludesList.isEmpty()) {
                    excludes = new String[xcludesList.size()];
                    for (int j = 0; j < xcludesList.size(); j++) {
                        IXMLElement xclude = xcludesList.get(j);
                        excludes[j] = requireAttribute(xclude, "name");
                    }
                }

                // parse additional fileset attributes "includes" and "excludes"
                String[] toDo = new String[]{"includes", "excludes"};
                // use the existing containers filled from include and exclude
                // and add the includes and excludes to it
                String[][] containers = new String[][]{includes, excludes};
                for (int j = 0; j < toDo.length; ++j) {
                    String inex = fileSetNode.getAttribute(toDo[j]);
                    if (inex != null && inex.length() > 0) { // This is the same "splitting" as ant PatternSet do ...
                        StringTokenizer tok = new StringTokenizer(inex, ", ", false);
                        int newSize = tok.countTokens();
                        int k = 0;
                        String[] nCont = null;
                        if (containers[j] != null && containers[j].length > 0) { // old container exist; create a new which can hold
                            // all values
                            // and copy the old stuff to the front
                            newSize += containers[j].length;
                            nCont = new String[newSize];
                            for (; k < containers[j].length; ++k) {
                                nCont[k] = containers[j][k];
                            }
                        }
                        if (nCont == null) // No container for old values
                        // created,
                        // create a new one.
                        {
                            nCont = new String[newSize];
                        }
                        for (; k < newSize; ++k)
                        // Fill the new one or expand the existent container
                        {
                            nCont[k] = tok.nextToken();
                        }
                        containers[j] = nCont;
                    }
                }
                includes = containers[0]; // push the new includes to the
                // local var
                excludes = containers[1]; // push the new excludes to the
                // local var

                // scan and add fileset
                DirectoryScanner ds = new DirectoryScanner();
                ds.setIncludes(includes);
                ds.setExcludes(excludes);
                if (defexcludes) {
                    ds.addDefaultExcludes();
                }
                ds.setBasedir(dir);
                ds.setCaseSensitive(casesensitive);
                ds.scan();

                String[] files = ds.getIncludedFiles();
                String[] dirs = ds.getIncludedDirectories();

                // Directory scanner has done recursion, add files and
                // directories
                for (String file : files) {
                    try {
                        String target = new File(targetdir, file).getPath();
                        pack.addFile(baseDir, new File(dir, file), target, osList, override,
                                blockable, additionals, condition);
                    }
                    catch (FileNotFoundException x) {
                        parseError(fileSetNode, x.getMessage(), x);
                    }
                }
                for (String dir1 : dirs) {
                    try {
                        String target = new File(targetdir, dir1).getPath();
                        pack.addFile(baseDir, new File(dir, dir1), target, osList, override,
                                blockable, additionals, condition);
                    }
                    catch (FileNotFoundException x) {
                        parseError(fileSetNode, x.getMessage(), x);
                    }
                }
            }

            // get the updatechecks list
            for (IXMLElement updateNode : packElement.getChildrenNamed("updatecheck")) {

                String casesensitive = updateNode.getAttribute("casesensitive");

                // get includes and excludes
                ArrayList<String> includesList = new ArrayList<String>();
                ArrayList<String> excludesList = new ArrayList<String>();

                // get includes and excludes
                for (IXMLElement ixmlElement1 : updateNode.getChildrenNamed("include")) {
                    includesList.add(requireAttribute(ixmlElement1, "name"));
                }

                for (IXMLElement ixmlElement : updateNode.getChildrenNamed("exclude")) {
                    excludesList.add(requireAttribute(ixmlElement, "name"));
                }

                pack.addUpdateCheck(new UpdateCheck(includesList, excludesList, casesensitive));
            }
            // We get the dependencies
            for (IXMLElement dependsNode : packElement.getChildrenNamed("depends")) {
                String depName = requireAttribute(dependsNode, "packname");
                pack.addDependency(depName);

            }

            for (IXMLElement validatorNode : packElement.getChildrenNamed("validator")) {
                pack.addValidator(requireContent(validatorNode));
            }

            // We add the pack
            compiler.addPack(pack);
        }

        for (IXMLElement refPackElement : refPackElements) {

            // get the name of reference xml file
            String refFileName = requireAttribute(refPackElement, "file");
            String selfcontained = refPackElement.getAttribute("selfcontained");
            boolean isselfcontained = Boolean.valueOf(selfcontained);

            // parsing ref-pack-set file
            IXMLElement refXMLData = this.readRefPackData(refFileName, isselfcontained);

            Debug.log("Reading refpack from " + refFileName);
            // Recursively call myself to add all packs and refpacks from the reference XML
            addPacksSingle(refXMLData);
        }

        for (IXMLElement refPackSet : refPackSets) {

            // the directory to scan
            String dir_attr = this.requireAttribute(refPackSet, "dir");

            File dir = new File(dir_attr);
            if (!dir.isAbsolute()) {
                dir = new File(basedir, dir_attr);
            }
            if (!dir.isDirectory()) // also tests '.exists()'
            {
                parseError(refPackSet, "Invalid refpackset directory 'dir': " + dir_attr);
            }

            // include pattern
            String includeString = this.requireAttribute(refPackSet, "includes");
            String[] includes = includeString.split(", ");

            // scan for refpack files
            DirectoryScanner ds = new DirectoryScanner();
            ds.setIncludes(includes);
            ds.setBasedir(dir);
            ds.setCaseSensitive(true);
            ds.scan();

            // loop through all found fils and handle them as normal refpack files
            String[] files = ds.getIncludedFiles();
            for (String file : files) {
                String refFileName = new File(dir, file).toString();

                // parsing ref-pack-set file
                IXMLElement refXMLData = this.readRefPackData(refFileName, false);

                // Recursively call myself to add all packs and refpacks from the reference XML
                addPacksSingle(refXMLData);
            }
        }

        notifyCompilerListener("addPacksSingle", CompilerListener.END, data);
    }

    private IXMLElement readRefPackData(String refFileName, boolean isselfcontained)
            throws CompilerException {
        File refXMLFile = new File(refFileName);
        if (!refXMLFile.isAbsolute()) {
            refXMLFile = new File(basedir, refFileName);
        }
        if (!refXMLFile.canRead()) {
            throw new CompilerException("Invalid file: " + refXMLFile);
        }

        InputStream specin;

        if (isselfcontained) {
            if (!refXMLFile.getAbsolutePath().endsWith(".zip")) {
                throw new CompilerException(
                        "Invalid file: " + refXMLFile
                                + ". Selfcontained files can only be of type zip.");
            }
            ZipFile zip;
            try {
                zip = new ZipFile(refXMLFile, ZipFile.OPEN_READ);
                ZipEntry specentry = zip.getEntry("META-INF/izpack.xml");
                specin = zip.getInputStream(specentry);
            }
            catch (IOException e) {
                throw new CompilerException("Error reading META-INF/izpack.xml in " + refXMLFile);
            }
        } else {
            try {
                specin = new FileInputStream(refXMLFile.getAbsolutePath());
            }
            catch (FileNotFoundException e) {
                throw new CompilerException(
                        "FileNotFoundException exception while reading refXMLFile");
            }
        }

        IXMLParser refXMLParser = new XMLParser();
        // We get it
        IXMLElement refXMLData = refXMLParser.parse(specin, refXMLFile.getAbsolutePath());

        // Now checked the loaded XML file for basic syntax
        // We check it
        if (!"installation".equalsIgnoreCase(refXMLData.getName())) {
            parseError(refXMLData, "this is not an IzPack XML installation file");
        }
        if (!CompilerData.VERSION.equalsIgnoreCase(requireAttribute(refXMLData, "version"))) {
            parseError(refXMLData, "the file version is different from the compiler version");
        }

        // Read the properties and perform replacement on the rest of the tree
        substituteProperties(refXMLData);

        // call addResources to add the referenced XML resources to this installation
        addResources(refXMLData);

        try {
            specin.close();
        }
        catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        return refXMLData;
    }

    /**
     * Add files in an archive to a pack
     *
     * @param archive     the archive file to unpack
     * @param targetdir   the target directory where the content of the archive will be installed
     * @param osList      The target OS constraints.
     * @param override    Overriding behaviour.
     * @param pack        Pack to be packed into
     * @param additionals Map which contains additional data
     * @param condition
     */
    protected void addArchiveContent(File baseDir, File archive, String targetdir,
                                     List<OsConstraint> osList, int override, int blockable,
                                     PackInfo pack, Map additionals,
                                     String condition) throws IOException {

        FileInputStream fin = new FileInputStream(archive);
        ZipInputStream zin = new ZipInputStream(fin);
        while (true) {
            ZipEntry zentry = zin.getNextEntry();
            if (zentry == null) {
                break;
            }
            if (zentry.isDirectory()) {
                continue;
            }

            try {
                File temp = File.createTempFile("izpack", null);
                temp.deleteOnExit();

                FileOutputStream out = new FileOutputStream(temp);
                PackagerHelper.copyStream(zin, out);
                out.close();

                pack.addFile(baseDir, temp, targetdir + "/" + zentry.getName(), osList, override,
                        blockable, additionals, condition);
            }
            catch (IOException e) {
                throw new IOException("Couldn't create temporary file for " + zentry.getName()
                        + " in archive " + archive + " (" + e.getMessage() + ")");
            }

        }
        fin.close();
    }

    /**
     * Recursive method to add files in a pack.
     *
     * @param file        The file to add.
     * @param targetdir   The relative path to the parent.
     * @param osList      The target OS constraints.
     * @param override    Overriding behaviour.
     * @param pack        Pack to be packed into
     * @param additionals Map which contains additional data
     * @param condition
     * @throws FileNotFoundException if the file does not exist
     */
    protected void addRecursively(File baseDir, File file, String targetdir,
                                  List<OsConstraint> osList, int override, int blockable,
                                  PackInfo pack, Map additionals, String condition) throws IOException {
        String targetfile = targetdir + "/" + file.getName();
        if (!file.isDirectory()) {
            pack.addFile(baseDir, file, targetfile, osList, override, blockable, additionals, condition);
        } else {
            File[] files = file.listFiles();
            if (files.length == 0) // The directory is empty so must be added
            {
                pack.addFile(baseDir, file, targetfile, osList, override, blockable, additionals, condition);
            } else {
                // new targetdir = targetfile;
                for (File file1 : files) {
                    addRecursively(baseDir, file1, targetfile, osList, override, blockable,
                            pack, additionals, condition);
                }
            }
        }
    }

    /**
     * Parse panels and their paramters, locate the panels resources and add to the Packager.
     *
     * @param data The XML data.
     * @throws CompilerException Description of the Exception
     */
    protected void addPanels(IXMLElement data) throws CompilerException {
        notifyCompilerListener("addPanels", CompilerListener.BEGIN, data);
        IXMLElement root = requireChildNamed(data, "panels");

        // at least one panel is required
        Vector<IXMLElement> panels = root.getChildrenNamed("panel");
        if (panels.isEmpty()) {
            parseError(root, "<panels> requires a <panel>");
        }

        // We process each panel markup
        // We need a panel counter to build unique panel dependet resource names
        int panelCounter = 0;
        for (IXMLElement panel1 : panels) {
            panelCounter++;

            // create the serialized Panel data
            Panel panel = new Panel();
            panel.osConstraints = OsConstraint.getOsList(panel1);
            String className = panel1.getAttribute("classname");
            // add an id
            String panelid = panel1.getAttribute("id");
            panel.setPanelid(panelid);
            String condition = panel1.getAttribute("condition");
            panel.setCondition(condition);

            // Panel files come in jars packaged w/ IzPack, or they can be
            // specified via a jar attribute on the panel element
            String jarPath = panel1.getAttribute("jar");
            if (jarPath == null) {
                jarPath = "bin/panels/" + className + ".jar";
            }
            URL url = null;
            // jar="" may be used to suppress the warning message ("Panel jar
            // file not found")
            if (!jarPath.equals("")) {
                url = findIzPackResource(jarPath, "Panel jar file", panel1, true);
            }

            // when the expected panel jar file is not found, it is assumed that
            // user will do the jar merge themselves via <jar> tag

            String fullClassName = null;
            if (url == null) {
                fullClassName = className;
            } else {
                try {
                    fullClassName = getFullClassName(url, className);
                }
                catch (IOException e) {
                }
            }

            if (fullClassName != null) {
                panel.className = fullClassName;
            } else {
                panel.className = className;
            }
            IXMLElement configurationElement = panel1.getFirstChildNamed("configuration");
            if (configurationElement != null) {
                Debug.trace("found a configuration for this panel.");
                Vector<IXMLElement> params = configurationElement.getChildrenNamed("param");
                if (params != null) {
                    for (IXMLElement param : params) {
                        IXMLElement keyElement = param.getFirstChildNamed("key");
                        IXMLElement valueElement = param.getFirstChildNamed("value");
                        if ((keyElement != null) && (valueElement != null)) {
                            panel.addConfiguration(keyElement.getContent(), valueElement.getContent());
                        }
                    }
                }
            }

            // adding validator
            IXMLElement validatorElement = panel1
                    .getFirstChildNamed(DataValidator.DATA_VALIDATOR_TAG);
            if (validatorElement != null) {
                String validator = validatorElement
                        .getAttribute(DataValidator.DATA_VALIDATOR_CLASSNAME_TAG);
                if (!"".equals(validator)) {
                    panel.setValidator(validator);
                }
            }
            // adding helps
            Vector helps = panel1.getChildrenNamed(AutomatedInstallData.HELP_TAG);
            if (helps != null) {
                for (Object help1 : helps) {
                    IXMLElement help = (IXMLElement) help1;
                    String iso3 = help.getAttribute(AutomatedInstallData.ISO3_ATTRIBUTE);
                    String resourceId;
                    if (panelid == null) {
                        resourceId = className + "_" + panelCounter + "_help_" + iso3 + ".html";
                    } else {
                        resourceId = panelid + "_" + panelCounter + "_help_" + iso3 + ".html";
                    }
                    panel.addHelp(iso3, resourceId);

                    URL originalUrl = findProjectResource(help
                            .getAttribute(AutomatedInstallData.SRC_ATTRIBUTE), "Help", help);
                    compiler.addResource(resourceId, originalUrl);
                }
            }
            // adding actions
            addPanelActions(panel1, panel);
            // insert into the packager
            compiler.addPanelJar(panel, url);
        }
        notifyCompilerListener("addPanels", CompilerListener.END, data);
    }

    /**
     * Adds the resources.
     *
     * @param data The XML data.
     * @throws CompilerException Description of the Exception
     */
    protected void addResources(IXMLElement data) throws CompilerException {
        notifyCompilerListener("addResources", CompilerListener.BEGIN, data);
        IXMLElement root = data.getFirstChildNamed("resources");
        if (root == null) {
            return;
        }

        // We process each res markup
        for (IXMLElement resNode : root.getChildrenNamed("res")) {
            String id = requireAttribute(resNode, "id");
            String src = requireAttribute(resNode, "src");
            // the parse attribute causes substitution to occur
            boolean substitute = validateYesNoAttribute(resNode, "parse", NO);
            // the parsexml attribute causes the xml document to be parsed
            boolean parsexml = validateYesNoAttribute(resNode, "parsexml", NO);

            String encoding = resNode.getAttribute("encoding");
            if (encoding == null) {
                encoding = "";
            }

            // basedir is not prepended if src is already an absolute path
            URL originalUrl = findProjectResource(src, "Resource", resNode);
            URL url = originalUrl;

            InputStream is = null;
            OutputStream os = null;
            try {
                if (!"".equals(encoding)) {
                    File recodedFile = File.createTempFile("izenc", null);
                    recodedFile.deleteOnExit();

                    InputStreamReader reader = new InputStreamReader(originalUrl.openStream(), encoding);
                    OutputStreamWriter writer = new OutputStreamWriter(
                            new FileOutputStream(recodedFile), "UTF-8");

                    char[] buffer = new char[1024];
                    int read;
                    while ((read = reader.read(buffer)) != -1) {
                        writer.write(buffer, 0, read);
                    }
                    reader.close();
                    writer.close();

                    originalUrl = recodedFile.toURL();
                }

                if (parsexml || (!"".equals(encoding)) || (substitute && !compiler.getVariables().isEmpty())) {
                    // make the substitutions into a temp file
                    File parsedFile = File.createTempFile("izpp", null);
                    parsedFile.deleteOnExit();
                    FileOutputStream outFile = new FileOutputStream(parsedFile);
                    os = new BufferedOutputStream(outFile);
                    // and specify the substituted file to be added to the
                    // packager
                    url = parsedFile.toURL();
                }

                if (parsexml) {
                    IXMLParser parser = new XMLParser();
                    // this constructor will open the specified url (this is
                    // why the InputStream is not handled in a similar manner
                    // to the OutputStream)

                    IXMLElement xml = parser.parse(originalUrl);
                    IXMLWriter writer = new XMLWriter();
                    if (substitute && !compiler.getVariables().isEmpty()) {
                        // if we are also performing substitutions on the file
                        // then create an in-memory copy to pass to the
                        // substitutor
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        writer.setOutput(baos);
                        is = new ByteArrayInputStream(baos.toByteArray());
                    } else {
                        // otherwise write direct to the temp file
                        writer.setOutput(os);
                    }
                    writer.write(xml);
                }

                // substitute variable values in the resource if parsed
                if (substitute) {
                    if (compiler.getVariables().isEmpty()) {
                        // reset url to original.
                        url = originalUrl;
                        parseWarn(resNode, "No variables defined. " + url.getPath() + " not parsed.");
                    } else {
                        SubstitutionType type = SubstitutionType.lookup(resNode.getAttribute("type"));

                        // if the xml parser did not open the url
                        // ('parsexml' was not enabled)
                        if (null == is) {
                            is = new BufferedInputStream(originalUrl.openStream());
                        }
                        VariableSubstitutor vs = new VariableSubstitutorImpl(compiler.getVariables());
                        vs.substitute(is, os, type, "UTF-8");
                    }
                }

            }
            catch (Exception e) {
                parseError(resNode, e.getMessage(), e);
            }
            finally {
                if (null != os) {
                    try {
                        os.close();
                    }
                    catch (IOException e) {
                        // ignore as there is nothing we can realistically do
                        // so lets at least try to close the input stream
                    }
                }
                if (null != is) {
                    try {
                        is.close();
                    }
                    catch (IOException e) {
                        // ignore as there is nothing we can realistically do
                    }
                }
            }

            compiler.addResource(id, url);

            // remembering references to all added packsLang.xml files
            if (id.startsWith("packsLang.xml")) {
                List<URL> packsLangURLs;
                if (packsLangUrlMap.containsKey(id)) {
                    packsLangURLs = packsLangUrlMap.get(id);
                } else {
                    packsLangURLs = new ArrayList<URL>();
                    packsLangUrlMap.put(id, packsLangURLs);
                }
                packsLangURLs.add(url);
            }

        }
        notifyCompilerListener("addResources", CompilerListener.END, data);
    }

    /**
     * Adds the ISO3 codes of the langpacks and associated resources.
     *
     * @param data The XML data.
     * @throws CompilerException Description of the Exception
     */
    protected void addLangpacks(IXMLElement data) throws CompilerException {
        notifyCompilerListener("addLangpacks", CompilerListener.BEGIN, data);
        IXMLElement root = requireChildNamed(data, "locale");

        // at least one langpack is required
        Vector<IXMLElement> locals = root.getChildrenNamed("langpack");
        if (locals.isEmpty()) {
            parseError(root, "<locale> requires a <langpack>");
        }

        // We process each langpack markup
        for (IXMLElement localNode : locals) {
            String iso3 = requireAttribute(localNode, "iso3");
            String path;

            path = "bin/langpacks/installer/" + iso3 + ".xml";
            URL iso3xmlURL = findIzPackResource(path, "ISO3 file", localNode);

            path = "bin/langpacks/flags/" + iso3 + ".gif";
            URL iso3FlagURL = findIzPackResource(path, "ISO3 flag image", localNode);

            compiler.addLangPack(iso3, iso3xmlURL, iso3FlagURL);
        }
        notifyCompilerListener("addLangpacks", CompilerListener.END, data);
    }

    /**
     * Builds the Info class from the XML tree.
     *
     * @param data The XML data. return The Info.
     * @throws Exception Description of the Exception
     */
    protected void addInfo(IXMLElement data) throws Exception {
        notifyCompilerListener("addInfo", CompilerListener.BEGIN, data);
        // Initialisation
        IXMLElement root = requireChildNamed(data, "info");

        Info info = new Info();
        info.setAppName(requireContent(requireChildNamed(root, "appname")));
        info.setAppVersion(requireContent(requireChildNamed(root, "appversion")));
        // We get the installation subpath
        IXMLElement subpath = root.getFirstChildNamed("appsubpath");
        if (subpath != null) {
            info.setInstallationSubPath(requireContent(subpath));
        }

        // validate and insert app URL
        final IXMLElement URLElem = root.getFirstChildNamed("url");
        if (URLElem != null) {
            URL appURL = requireURLContent(URLElem);
            info.setAppURL(appURL.toString());
        }

        // We get the authors list
        IXMLElement authors = root.getFirstChildNamed("authors");
        if (authors != null) {
            for (IXMLElement authorNode : authors.getChildrenNamed("author")) {
                String name = requireAttribute(authorNode, "name");
                String email = requireAttribute(authorNode, "email");
                info.addAuthor(new Info.Author(name, email));
            }
        }

        // We get the java version required
        IXMLElement javaVersion = root.getFirstChildNamed("javaversion");
        if (javaVersion != null) {
            info.setJavaVersion(requireContent(javaVersion));
        }

        // Is a JDK required?
        IXMLElement jdkRequired = root.getFirstChildNamed("requiresjdk");
        if (jdkRequired != null) {
            info.setJdkRequired("yes".equals(jdkRequired.getContent()));
        }

        // validate and insert (and require if -web kind) web dir
        IXMLElement webDirURL = root.getFirstChildNamed("webdir");
        if (webDirURL != null) {
            info.setWebDirURL(requireURLContent(webDirURL).toString());
        }
        String kind = compiler.getKind();
        if (kind != null) {
            if (kind.equalsIgnoreCase(CompilerData.WEB) && webDirURL == null) {
                parseError(root, "<webdir> required when \"WEB\" installer requested");
            } else if (kind.equalsIgnoreCase(CompilerData.STANDARD) && webDirURL != null) {
                // Need a Warning? parseWarn(webDirURL, "Not creating web
                // installer.");
                info.setWebDirURL(null);
            }
        }

        // Pack200 support
        IXMLElement pack200 = root.getFirstChildNamed("pack200");
        info.setPack200Compression(pack200 != null);

        // Privileged execution
        IXMLElement privileged = root.getFirstChildNamed("run-privileged");
        info.setRequirePrivilegedExecution(privileged != null);
        if (privileged != null && privileged.hasAttribute("condition")) {
            info.setPrivilegedExecutionConditionID(privileged.getAttribute("condition"));
        }

        // Reboot if necessary
        IXMLElement reboot = root.getFirstChildNamed("rebootaction");
        if (reboot != null) {
            String content = reboot.getContent();
            if ("ignore".equalsIgnoreCase(content))
                info.setRebootAction(Info.REBOOT_ACTION_IGNORE);
            else if ("notice".equalsIgnoreCase(content))
                info.setRebootAction(Info.REBOOT_ACTION_NOTICE);
            else if ("ask".equalsIgnoreCase(content))
                info.setRebootAction(Info.REBOOT_ACTION_ASK);
            else if ("always".equalsIgnoreCase(content))
                info.setRebootAction(Info.REBOOT_ACTION_ALWAYS);
            else
                throw new CompilerException("Invalid value ''" + content + "'' of element ''reboot''");

            if (reboot.hasAttribute("condition")) {
                info.setRebootActionConditionID(reboot.getAttribute("condition"));
            }
        }

        // Add the uninstaller as a resource if specified
        IXMLElement uninstallInfo = root.getFirstChildNamed("uninstaller");
        if (validateYesNoAttribute(uninstallInfo, "write", YES)) {
            URL url = findIzPackResource(compiler.getProperty("uninstaller"), "Uninstaller", root);
            compiler.addResource("IzPack.uninstaller", url);

            if (privileged != null) {
                // default behavior for uninstaller elevation: elevate if installer has to be elevated too
                info.setRequirePrivilegedExecutionUninstaller(validateYesNoAttribute(privileged,
                        "uninstaller", YES));
            }

            if (uninstallInfo != null) {
                String uninstallerName = uninstallInfo.getAttribute("name");
                if (uninstallerName != null && uninstallerName.length() > ".jar".length()) {
                    info.setUninstallerName(uninstallerName);
                }
                String uninstallerPath = uninstallInfo.getAttribute("path");
                if (uninstallerPath != null) {
                    info.setUninstallerPath(uninstallerPath);
                }
                if (uninstallInfo.hasAttribute("condition")) {
                    // there's a condition for uninstaller
                    String uninstallerCondition = uninstallInfo.getAttribute("condition");
                    info.setUninstallerCondition(uninstallerCondition);
                }
            }
        }

        // Add the path for the summary log file if specified
        IXMLElement slfPath = root.getFirstChildNamed("summarylogfilepath");
        if (slfPath != null) {
            info.setSummaryLogFilePath(requireContent(slfPath));
        }

        IXMLElement writeInstallInfo = root.getFirstChildNamed("writeinstallationinformation");
        if (writeInstallInfo != null) {
            String writeInstallInfoString = requireContent(writeInstallInfo);
            info.setWriteInstallationInformation(validateYesNo(writeInstallInfoString));
        }

        // look for an unpacker class
        String unpackerclass = compiler.getProperty("UNPACKER_CLASS");
        info.setUnpackerClassName(unpackerclass);
        compiler.setInfo(info);
        notifyCompilerListener("addInfo", CompilerListener.END, data);
    }

    /**
     * Variable declaration is a fragment of the xml file. For example: <p/>
     * <p/>
     * <pre>
     * &lt;p/&gt;
     * &lt;p/&gt;
     * &lt;p/&gt;
     * &lt;p/&gt;
     *        &lt;variables&gt;
     *          &lt;variable name=&quot;nom&quot; value=&quot;value&quot;/&gt;
     *          &lt;variable name=&quot;foo&quot; value=&quot;pippo&quot;/&gt;
     *        &lt;/variables&gt;
     * &lt;p/&gt;
     * &lt;p/&gt;
     * &lt;p/&gt;
     * &lt;p/&gt;
     * </pre>
     * <p/>
     * <p/> variable declared in this can be referred to in parsable files.
     *
     * @param data The XML data.
     * @throws CompilerException Description of the Exception
     */
    protected void addVariables(IXMLElement data) throws CompilerException {
        notifyCompilerListener("addVariables", CompilerListener.BEGIN, data);
        // We get the varible list
        IXMLElement root = data.getFirstChildNamed("variables");
        if (root == null) {
            return;
        }

        Properties variables = compiler.getVariables();

        for (IXMLElement variableNode : root.getChildrenNamed("variable")) {
            String name = requireAttribute(variableNode, "name");
            String value = requireAttribute(variableNode, "value");
            if (variables.contains(name)) {
                parseWarn(variableNode, "Variable '" + name + "' being overwritten");
            }
            variables.setProperty(name, value);
        }
        notifyCompilerListener("addVariables", CompilerListener.END, data);
    }

    protected void addDynamicVariables(IXMLElement data) throws CompilerException {
        notifyCompilerListener("addDynamicVariables", CompilerListener.BEGIN, data);
        // We get the dynamic variable list
        IXMLElement root = data.getFirstChildNamed("dynamicvariables");
        if (root == null) {
            return;
        }

        Map<String, List<DynamicVariable>> dynamicvariables = compiler.getDynamicVariables();

        for (IXMLElement variableNode : root.getChildrenNamed("variable")) {
            String name = requireAttribute(variableNode, "name");
            String value = variableNode.getAttribute("value");
            if (value == null) {
                IXMLElement valueElement = variableNode.getFirstChildNamed("value");
                if (valueElement != null) {
                    value = valueElement.getContent();
                    if (value == null) {
                        parseError("A dynamic variable needs either a value attribute or a value element.");
                    }
                } else {
                    parseError("A dynamic variable needs either a value attribute or a value element. Variable name: " + name);
                }
            }
            String conditionid = variableNode.getAttribute("condition");

            List<DynamicVariable> dynamicValues = new ArrayList<DynamicVariable>();
            if (dynamicvariables.containsKey(name)) {
                dynamicValues = dynamicvariables.get(name);
            } else {
                dynamicvariables.put(name, dynamicValues);
            }

            DynamicVariable dynamicVariable = new DynamicVariable();
            dynamicVariable.setName(name);
            dynamicVariable.setValue(value);
            dynamicVariable.setConditionid(conditionid);
            if (dynamicValues.remove(dynamicVariable)) {
                parseWarn(variableNode, "Dynamic Variable '" + name + "' will be overwritten");
            }
            dynamicValues.add(dynamicVariable);
        }
        notifyCompilerListener("addDynamicVariables", CompilerListener.END, data);
    }

    /**
     * Parse conditions and add them to the compiler.
     *
     * @param data
     * @throws CompilerException
     */
    protected void addConditions(IXMLElement data) throws CompilerException {
        notifyCompilerListener("addConditions", CompilerListener.BEGIN, data);
        // We get the condition list
        IXMLElement root = data.getFirstChildNamed("conditions");
        Map<String, Condition> conditions = compiler.getConditions();
        if (root != null) {
            for (IXMLElement conditionNode : root.getChildrenNamed("condition")) {
                Condition condition = RulesEngineImpl.analyzeCondition(conditionNode);
                if (condition != null) {
                    String conditionid = condition.getId();
                    if (conditions.containsKey(conditionid)) {
                        parseWarn(conditionNode, "Condition with id '" + conditionid
                                + "' will be overwritten");
                    }
                    conditions.put(conditionid, condition);

                } else {
                    parseWarn(conditionNode, "Condition couldn't be instantiated.");
                }
            }
        }
        notifyCompilerListener("addConditions", CompilerListener.END, data);
    }

    /**
     * Properties declaration is a fragment of the xml file. For example: <p/>
     * <p/>
     * <pre>
     * &lt;p/&gt;
     * &lt;p/&gt;
     * &lt;p/&gt;
     * &lt;p/&gt;
     *        &lt;properties&gt;
     *          &lt;property name=&quot;app.name&quot; value=&quot;Property Laden Installer&quot;/&gt;
     *          &lt;!-- Ant styles 'location' and 'refid' are not yet supported --&gt;
     *          &lt;property file=&quot;filename-relative-to-install?&quot;/&gt;
     *          &lt;property file=&quot;filename-relative-to-install?&quot; prefix=&quot;prefix&quot;/&gt;
     *          &lt;!-- Ant style 'url' and 'resource' are not yet supported --&gt;
     *          &lt;property environment=&quot;prefix&quot;/&gt;
     *        &lt;/properties&gt;
     * &lt;p/&gt;
     * &lt;p/&gt;
     * &lt;p/&gt;
     * &lt;p/&gt;
     * </pre>
     * <p/>
     * <p/> variable declared in this can be referred to in parsable files.
     *
     * @param data The XML data.
     * @throws CompilerException Description of the Exception
     */
    protected void substituteProperties(IXMLElement data) throws CompilerException {
        notifyCompilerListener("substituteProperties", CompilerListener.BEGIN, data);

        IXMLElement root = data.getFirstChildNamed("properties");
        if (root != null) {
            // add individual properties
            for (IXMLElement propertyNode : root.getChildrenNamed("property")) {
                Property property = new Property(propertyNode, this);
                property.execute();
            }
        }

        // temporarily remove the 'properties' branch, replace all properties in
        // the remaining DOM, and replace properties branch.
        // TODO: enhance IXMLElement with an "indexOf(IXMLElement)" method
        // and addChild(IXMLElement, int) so returns to the same place.
        if (root != null) {
            data.removeChild(root);
        }

        substituteAllProperties(data);
        if (root != null) {
            data.addChild(root);
        }

        notifyCompilerListener("substituteProperties", CompilerListener.END, data);
    }

    /**
     * Perform recursive substitution on all properties
     */
    protected void substituteAllProperties(IXMLElement element) throws CompilerException {
        Enumeration attributes = element.enumerateAttributeNames();
        while (attributes.hasMoreElements()) {
            String name = (String) attributes.nextElement();
            String value = compiler.replaceProperties(element.getAttribute(name));
            element.setAttribute(name, value);
        }

        String content = element.getContent();
        if (content != null) {
            element.setContent(compiler.replaceProperties(content));
        }

        for (int i = 0; i < element.getChildren().size(); i++) {
            IXMLElement child = (IXMLElement) element.getChildren().elementAt(i);
            substituteAllProperties(child);
        }
    }

    /**
     * Checks whether a File instance is a regular file, exists and is readable. Throws appropriate
     * CompilerException to report violations of these conditions.
     *
     * @throws CompilerException if the file is either not existing, not a regular file or not
     *                           readable.
     */
    private void assertIsNormalReadableFile(File fileToCheck, String fileDescription)
            throws CompilerException {
        if (fileToCheck != null) {
            if (!fileToCheck.exists()) {
                throw new CompilerException(fileDescription
                        + " does not exist: " + fileToCheck);
            }
            if (!fileToCheck.isFile()) {
                throw new CompilerException(fileDescription
                        + " is not a regular file: " + fileToCheck);
            }
            if (!fileToCheck.canRead()) {
                throw new CompilerException(fileDescription
                        + " is not readable by application: " + fileToCheck);
            }
        }
    }

    /**
     * Returns the IXMLElement representing the installation XML file.
     *
     * @return The XML tree.
     * @throws CompilerException For problems with the installation file
     * @throws IOException       for errors reading the installation file
     */
    protected IXMLElement getXMLTree() throws IOException {
        IXMLParser parser = new XMLParser();
        IXMLElement data;
        if (filename != null) {
            File file = new File(filename).getAbsoluteFile();
            assertIsNormalReadableFile(file, "Configuration file");
            data = parser.parse(new FileInputStream(filename), file.getAbsolutePath());
            // add izpack built in property
            compiler.setProperty("izpack.file", file.toString());
        } else if (installText != null) {
            data = parser.parse(installText);
        } else {
            throw new CompilerException("Neither install file nor text specified");
        }
        // We check it
        if (!"installation".equalsIgnoreCase(data.getName())) {
            parseError(data, "this is not an IzPack XML installation file");
        }
        if (!CompilerData.VERSION.equalsIgnoreCase(requireAttribute(data, "version"))) {
            parseError(data, "the file version is different from the compiler version");
        }

        // We finally return the tree
        return data;
    }

    protected int getOverrideValue(IXMLElement f) throws CompilerException {
        int override = PackFile.OVERRIDE_UPDATE;

        String override_val = f.getAttribute("override");
        if (override_val != null) {
            if ("true".equalsIgnoreCase(override_val)) {
                override = PackFile.OVERRIDE_TRUE;
            } else if ("false".equalsIgnoreCase(override_val)) {
                override = PackFile.OVERRIDE_FALSE;
            } else if ("asktrue".equalsIgnoreCase(override_val)) {
                override = PackFile.OVERRIDE_ASK_TRUE;
            } else if ("askfalse".equalsIgnoreCase(override_val)) {
                override = PackFile.OVERRIDE_ASK_FALSE;
            } else if ("update".equalsIgnoreCase(override_val)) {
                override = PackFile.OVERRIDE_UPDATE;
            } else {
                parseError(f, "invalid value for attribute \"override\"");
            }
        }

        return override;
    }

    /**
     * Parses the blockable element value and adds automatically the OS constraint
     * family=windows if not already se in the given constraint list.
     * Throws a parsing warning if the constraint list was implicitely modified.
     *
     * @param f      the blockable XML element to parse
     * @param osList constraint list to maintain and return
     * @return blockable level
     * @throws CompilerException
     */
    protected int getBlockableValue(IXMLElement f, List<OsConstraint> osList) throws CompilerException {
        int blockable = PackFile.BLOCKABLE_NONE;

        String blockable_val = f.getAttribute("blockable");
        if (blockable_val != null) {
            if ("none".equalsIgnoreCase(blockable_val)) {
                blockable = PackFile.BLOCKABLE_NONE;
            } else if ("auto".equalsIgnoreCase(blockable_val)) {
                blockable = PackFile.BLOCKABLE_AUTO;
            } else if ("force".equalsIgnoreCase(blockable_val)) {
                blockable = PackFile.BLOCKABLE_FORCE;
            } else {
                parseError(f, "invalid value for attribute \"blockable\"");
            }
        }

        if (blockable != PackFile.BLOCKABLE_NONE) {
            boolean found = false;
            for (OsConstraint anOsList : osList) {
                if ("windows".equals(anOsList.getFamily())) {
                    found = true;
                }
            }

            if (!found) {
                // We cannot add this constraint here explicitely, because it
                // the copied files might be multi-platform.
                // Print out a warning to inform the user about this fact.
                //osList.add(new OsConstraint("windows", null, null, null));
                parseWarn(f, "'blockable' will implicitely apply only on Windows target systems");
            }
        }

        return blockable;
    }

    /**
     * Look for a project specified resources, which, if not absolute, are sought relative to the
     * projects basedir. The path should use '/' as the fileSeparator. If the resource is not found,
     * a CompilerException is thrown indicating fault in the parent element.
     *
     * @param path   the relative path (using '/' as separator) to the resource.
     * @param desc   the description of the resource used to report errors
     * @param parent the IXMLElement the resource is specified in, used to report errors
     * @return a URL to the resource.
     */
    private URL findProjectResource(String path, String desc, IXMLElement parent)
            throws CompilerException {
        URL url = null;
        File resource = new File(path);
        if (!resource.isAbsolute()) {
            resource = new File(basedir, path);
        }

        if (!resource.exists()) // fatal
        {
            parseError(parent, desc + " not found: " + resource);
        }

        try {
            url = resource.toURL();
        }
        catch (MalformedURLException how) {
            parseError(parent, desc + "(" + resource + ")", how);
        }

        return url;
    }

    private URL findIzPackResource(String path, String desc, IXMLElement parent)
            throws CompilerException {
        return findIzPackResource(path, desc, parent, false);
    }

    /**
     * Look for an IzPack resource either in the compiler jar, or within IZPACK_HOME. The path must
     * not be absolute. The path must use '/' as the fileSeparator (it's used to access the jar
     * file). If the resource is not found, take appropriate action base on ignoreWhenNotFound flag.
     *
     * @param path               the relative path (using '/' as separator) to the resource.
     * @param desc               the description of the resource used to report errors
     * @param parent             the IXMLElement the resource is specified in, used to report errors
     * @param ignoreWhenNotFound when false, throws a CompilerException indicating
     *                           fault in the parent element when resource not found.
     * @return a URL to the resource.
     */
    private URL findIzPackResource(String path, String desc, IXMLElement parent, boolean ignoreWhenNotFound)
            throws CompilerException {
        URL url = getClass().getResource("/" + path);
        if (url == null) {
            File resource = new File(path);

            if (!resource.isAbsolute()) {
                resource = new File(CompilerData.IZPACK_HOME, path);
            }

            if (resource.exists()) {
                try {
                    url = resource.toURL();
                }
                catch (MalformedURLException how) {
                    parseError(parent, desc + "(" + resource + ")", how);
                }
            } else {
                if (ignoreWhenNotFound) {
                    parseWarn(parent, desc + " not found: " + resource);
                } else {
                    parseError(parent, desc + " not found: " + resource);
                }
            }

        }

        return url;
    }

    /**
     * Create parse error with consistent messages. Includes file name. For use When parent is
     * unknown.
     *
     * @param message Brief message explaining error
     */
    protected void parseError(String message) throws CompilerException {
        throw new CompilerException(filename + ":" + message);
    }

    /**
     * Create parse error with consistent messages. Includes file name and line # of parent. It is
     * an error for 'parent' to be null.
     *
     * @param parent  The element in which the error occured
     * @param message Brief message explaining error
     */
    protected void parseError(IXMLElement parent, String message) throws CompilerException {
        throw new CompilerException(filename + ":" + parent.getLineNr() + ": " + message);
    }

    /**
     * Create a chained parse error with consistent messages. Includes file name and line # of
     * parent. It is an error for 'parent' to be null.
     *
     * @param parent  The element in which the error occured
     * @param message Brief message explaining error
     */
    protected void parseError(IXMLElement parent, String message, Throwable cause)
            throws CompilerException {
        throw new CompilerException(filename + ":" + parent.getLineNr() + ": " + message, cause);
    }

    /**
     * Create a parse warning with consistent messages. Includes file name and line # of parent. It
     * is an error for 'parent' to be null.
     *
     * @param parent  The element in which the warning occured
     * @param message Warning message
     */
    protected void parseWarn(IXMLElement parent, String message) {
        System.out.println("Warning: " + filename + ":" + parent.getLineNr() + ": " + message);
    }

    /**
     * Call getFirstChildNamed on the parent, producing a meaningful error message on failure. It is
     * an error for 'parent' to be null.
     *
     * @param parent The element to search for a child
     * @param name   Name of the child element to get
     */
    protected IXMLElement requireChildNamed(IXMLElement parent, String name) throws CompilerException {
        IXMLElement child = parent.getFirstChildNamed(name);
        if (child == null) {
            parseError(parent, "<" + parent.getName() + "> requires child <" + name + ">");
        }
        return child;
    }

    /**
     * Call getContent on an element, producing a meaningful error message if not present, or empty,
     * or a valid URL. It is an error for 'element' to be null.
     *
     * @param element The element to get content of
     */
    protected URL requireURLContent(IXMLElement element) throws CompilerException {
        URL url = null;
        try {
            url = new URL(requireContent(element));
        }
        catch (MalformedURLException x) {
            parseError(element, "<" + element.getName() + "> requires valid URL", x);
        }
        return url;
    }

    /**
     * Call getContent on an element, producing a meaningful error message if not present, or empty.
     * It is an error for 'element' to be null.
     *
     * @param element The element to get content of
     */
    protected String requireContent(IXMLElement element) throws CompilerException {
        String content = element.getContent();
        if (content == null || content.length() == 0) {
            parseError(element, "<" + element.getName() + "> requires content");
        }
        return content;
    }

    protected boolean validateYesNo(String value) {
        boolean result;
        if ("yes".equalsIgnoreCase(value)) {
            result = true;
        } else if ("no".equalsIgnoreCase(value)) {
            result = false;
        } else {
            Debug.trace("yes/no not found. trying true/false");
            result = Boolean.valueOf(value);
        }
        return result;
    }

    /**
     * Call getAttribute on an element, producing a meaningful error message if not present, or
     * empty. It is an error for 'element' or 'attribute' to be null.
     *
     * @param element   The element to get the attribute value of
     * @param attribute The name of the attribute to get
     */
    protected String requireAttribute(IXMLElement element, String attribute)
            throws CompilerException {
        String value = element.getAttribute(attribute);
        if (value == null) {
            parseError(element, "<" + element.getName() + "> requires attribute '" + attribute
                    + "'");
        }
        return value;
    }

    /**
     * Get a required attribute of an element, ensuring it is an integer. A meaningful error message
     * is generated as a CompilerException if not present or parseable as an int. It is an error for
     * 'element' or 'attribute' to be null.
     *
     * @param element   The element to get the attribute value of
     * @param attribute The name of the attribute to get
     */
    protected int requireIntAttribute(IXMLElement element, String attribute)
            throws CompilerException {
        String value = element.getAttribute(attribute);
        if (value == null || value.length() == 0) {
            parseError(element, "<" + element.getName() + "> requires attribute '" + attribute
                    + "'");
        }
        try {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException x) {
            parseError(element, "'" + attribute + "' must be an integer");
        }
        return 0; // never happens
    }

    /**
     * Call getAttribute on an element, producing a meaningful error message if not present, or one
     * of "yes" or "no". It is an error for 'element' or 'attribute' to be null.
     *
     * @param element   The element to get the attribute value of
     * @param attribute The name of the attribute to get
     */
    protected boolean requireYesNoAttribute(IXMLElement element, String attribute)
            throws CompilerException {
        String value = requireAttribute(element, attribute);
        if ("yes".equalsIgnoreCase(value)) {
            return true;
        }
        if ("no".equalsIgnoreCase(value)) {
            return false;
        }

        parseError(element, "<" + element.getName() + "> invalid attribute '" + attribute
                + "': Expected (yes|no)");

        return false; // never happens
    }

    /**
     * Call getAttribute on an element, producing a meaningful warning if not "yes" or "no". If the
     * 'element' or 'attribute' are null, the default value is returned.
     *
     * @param element      The element to get the attribute value of
     * @param attribute    The name of the attribute to get
     * @param defaultValue Value returned if attribute not present or invalid
     */
    protected boolean validateYesNoAttribute(IXMLElement element, String attribute,
                                             boolean defaultValue) {
        if (element == null) {
            return defaultValue;
        }

        String value = element.getAttribute(attribute, (defaultValue ? "yes" : "no"));
        if ("yes".equalsIgnoreCase(value)) {
            return true;
        }
        if ("no".equalsIgnoreCase(value)) {
            return false;
        }

        // TODO: should this be an error if it's present but "none of the
        // above"?
        parseWarn(element, "<" + element.getName() + "> invalid attribute '" + attribute
                + "': Expected (yes|no) if present");

        return defaultValue;
    }


    // -------------------------------------------------------------------------
    // ------------- Listener stuff ------------------------- START ------------

    /**
     * This method parses install.xml for defined listeners and put them in the right position. If
     * posible, the listeners will be validated. Listener declaration is a fragmention in
     * install.xml like : <listeners> <listener compiler="PermissionCompilerListener"
     * installer="PermissionInstallerListener"/> </<listeners>
     *
     * @param data the XML data
     * @throws Exception Description of the Exception
     */
    private void addCustomListeners(IXMLElement data) throws Exception {
        // We get the listeners
        IXMLElement root = data.getFirstChildNamed("listeners");
        if (root == null) {
            return;
        }
        for (IXMLElement ixmlElement : root.getChildrenNamed("listener")) {
            Object[] listener = getCompilerListenerInstance(ixmlElement);
            if (listener != null) {
                addCompilerListener((CompilerListener) listener[0]);
            }
            String[] typeNames = new String[]{"installer", "uninstaller"};
            int[] types = new int[]{CustomData.INSTALLER_LISTENER, CustomData.UNINSTALLER_LISTENER};

            for (int i = 0; i < typeNames.length; ++i) {
                String className = ixmlElement.getAttribute(typeNames[i]);
                if (className != null) {
                    // Check for a jar attribute on the listener
                    String jarPath = ixmlElement.getAttribute("jar");
                    jarPath = compiler.replaceProperties(jarPath);
                    if (jarPath == null) {
                        jarPath = "bin/customActions/" + className + ".jar";
                        if (!new File(jarPath).exists()) {
                            jarPath = compilerHelper.resolveCustomActionsJarPath(className);
                        }
                    }
                    List<OsConstraint> constraints = OsConstraint.getOsList(ixmlElement);
                    compiler.addCustomListener(types[i], className, jarPath, constraints);
                }
            }
        }

    }

    /**
     * Returns a list which contains the pathes of all files which are included in the given url.
     * This method expects as the url param a jar.
     *
     * @param url url of the jar file
     * @return full qualified paths of the contained files
     * @throws Exception
     */
    private List<String> getContainedFilePaths(URL url) throws Exception {
        JarInputStream jis = new JarInputStream(url.openStream());
        ZipEntry zentry;
        ArrayList<String> fullNames = new ArrayList<String>();
        while ((zentry = jis.getNextEntry()) != null) {
            String name = zentry.getName();
            // Add only files, no directory entries.
            if (!zentry.isDirectory()) {
                fullNames.add(name);
            }
        }
        jis.close();
        return (fullNames);
    }

    /**
     * Returns the qualified class name for the given class. This method expects as the url param a
     * jar file which contains the given class. It scans the zip entries of the jar file.
     *
     * @param url       url of the jar file which contains the class
     * @param className short name of the class for which the full name should be resolved
     * @return full qualified class name
     * @throws IOException
     */
    private String getFullClassName(URL url, String className) throws IOException // throws
    // Exception
    {
        JarInputStream jis = new JarInputStream(url.openStream());
        ZipEntry zentry;
        while ((zentry = jis.getNextEntry()) != null) {
            String name = zentry.getName();
            int lastPos = name.lastIndexOf(".class");
            if (lastPos < 0) {
                continue; // No class file.
            }
            name = name.replace('/', '.');
            int pos = -1;
            int nonCasePos = -1;
            if (className != null) {
                pos = name.indexOf(className);
                nonCasePos = name.toLowerCase().indexOf(className.toLowerCase());
            }
            if (pos != -1 && name.length() == pos + className.length() + 6) // "Main" class found
            {
                jis.close();
                return (name.substring(0, lastPos));
            }

            if (nonCasePos != -1 && name.length() == nonCasePos + className.length() + 6)
            // "Main" class with different case found
            {
                throw new IllegalArgumentException(
                        "Fatal error! The declared panel name in the xml file (" + className
                                + ") differs in case to the founded class file (" + name + ").");
            }
        }
        jis.close();
        return (null);
    }

    /**
     * Returns the compiler listener which is defined in the xml element. As xml element a "listner"
     * node will be expected. Additional it is expected, that either "findIzPackResource" returns an
     * url based on "bin/customActions/[className].jar", or that the listener element has a jar
     * attribute specifying the listener jar path. The class will be loaded via an URLClassLoader.
     *
     * @param var the xml element of the "listener" node
     * @return instance of the defined compiler listener
     * @throws Exception
     */
    private Object[] getCompilerListenerInstance(IXMLElement var) throws Exception {
        String className = var.getAttribute("compiler");
        Class listener = null;
        Object instance = null;
        if (className == null) {
            return (null);
        }

        // CustomAction files come in jars packaged IzPack, or they can be
        // specified via a jar attribute on the listener
        String jarPath = var.getAttribute("jar");
        jarPath = compiler.replaceProperties(jarPath);
        if (jarPath == null) {
            jarPath = "bin/customActions/" + className + ".jar";
        }
        URL url = findIzPackResource(jarPath, "CustomAction jar file", var);
        String fullName = getFullClassName(url, className);
        if (fullName == null) {
            // class not found
            return null;
        }
        if (url != null) {
            if (getClass().getResource("/" + jarPath) != null) { // Oops, standalone, URLClassLoader will not work ...
                // Write the jar to a temp file.
                InputStream in = null;
                FileOutputStream outFile = null;
                byte[] buffer = new byte[5120];
                File tf = null;
                try {
                    tf = File.createTempFile("izpj", ".jar");
                    tf.deleteOnExit();
                    outFile = new FileOutputStream(tf);
                    in = getClass().getResourceAsStream("/" + jarPath);
                    long bytesCopied = 0;
                    int bytesInBuffer;
                    while ((bytesInBuffer = in.read(buffer)) != -1) {
                        outFile.write(buffer, 0, bytesInBuffer);
                        bytesCopied += bytesInBuffer;
                    }
                }
                finally {
                    if (in != null) {
                        in.close();
                    }
                    if (outFile != null) {
                        outFile.close();
                    }
                }
                url = tf.toURL();

            }
            // Use the class loader of the interface as parent, else
            // compile will fail at using it via an Ant task.
            URLClassLoader ucl = new URLClassLoader(new URL[]{url}, CompilerListener.class
                    .getClassLoader());
            listener = ucl.loadClass(fullName);
        }
        if (listener != null) {
            instance = listener.newInstance();
        } else {
            parseError(var, "Cannot find defined compiler listener " + className);
        }
        if (!CompilerListener.class.isInstance(instance)) {
            parseError(var, "'" + className + "' must be implemented "
                    + CompilerListener.class.toString());
        }
        List<OsConstraint> constraints = OsConstraint.getOsList(var);
        return (new Object[]{instance, className, constraints});
    }

    /**
     * Add a CompilerListener. A registered CompilerListener will be called at every enhancmend
     * point of compiling.
     *
     * @param pe CompilerListener which should be added
     */
    private void addCompilerListener(CompilerListener pe) {
        compilerListeners.add(pe);
    }

    /**
     * Calls all defined compile listeners notify method with the given data
     *
     * @param callerName name of the calling method as string
     * @param state      CompileListener.BEGIN or END
     * @param data       current install data
     * @throws CompilerException
     */
    private void notifyCompilerListener(String callerName, int state, IXMLElement data) {
        IPackager packager = compiler.getPackager();
        for (CompilerListener compilerListener : compilerListeners) {
            compilerListener.notify(callerName, state, data, packager);
        }

    }

    /**
     * Calls the reviseAdditionalDataMap method of all registered CompilerListener's.
     *
     * @param f file releated XML node
     * @return a map with the additional attributes
     */
    private Map getAdditionals(IXMLElement f) throws CompilerException {
        Map retval = null;
        try {
            for (CompilerListener compilerListener : compilerListeners) {
                retval = (compilerListener).reviseAdditionalDataMap(retval, f);
            }
        }
        catch (CompilerException ce) {
            parseError(f, ce.getMessage());
        }
        return (retval);
    }

    /**
     * A function to merge multiple packsLang-files into a single file for each identifier, e.g. two
     * resource files
     * <p/>
     * <pre>
     *    &lt;res src=&quot;./packsLang01.xml&quot; id=&quot;packsLang.xml&quot;/&gt;
     *    &lt;res src=&quot;./packsLang02.xml&quot; id=&quot;packsLang.xml&quot;/&gt;
     * </pre>
     * <p/>
     * are merged into a single temp-file to act as if the user had defined:
     * <p/>
     * <pre>
     *    &lt;res src=&quot;/tmp/izpp47881.tmp&quot; id=&quot;packsLang.xml&quot;/&gt;
     * </pre>
     *
     * @throws CompilerException
     */
    private void mergePacksLangFiles() throws CompilerException {
        // just one packslang file. nothing to do here
        if (packsLangUrlMap.size() <= 0) return;

        OutputStream os = null;
        try {
            IXMLParser parser = new XMLParser();

            // loop through all packsLang resources, e.g. packsLang.xml_eng, packsLang.xml_deu, ...
            for (String id : packsLangUrlMap.keySet()) {
                URL mergedPackLangFileURL;

                List<URL> packsLangURLs = packsLangUrlMap.get(id);
                if (packsLangURLs.size() == 0) continue; // should not occure

                if (packsLangURLs.size() == 1) {
                    // no need to merge files. just use the first URL
                    mergedPackLangFileURL = packsLangURLs.get(0);
                } else {
                    IXMLElement mergedPacksLang = null;

                    // loop through all that belong to the given identifier
                    for (URL packslangURL : packsLangURLs) {
                        // parsing xml
                        IXMLElement xml = parser.parse(packslangURL);
                        if (mergedPacksLang == null) {
                            // just keep the first file
                            mergedPacksLang = xml;
                        } else {
                            // append data of all xml-docs into the first document
                            Vector<IXMLElement> langStrings = xml.getChildrenNamed("str");
                            for (IXMLElement langString : langStrings) {
                                mergedPacksLang.addChild(langString);
                            }
                        }
                    }

                    // writing merged strings to a new file
                    File mergedPackLangFile = File.createTempFile("izpp", null);
                    mergedPackLangFile.deleteOnExit();

                    FileOutputStream outFile = new FileOutputStream(mergedPackLangFile);
                    os = new BufferedOutputStream(outFile);

                    IXMLWriter xmlWriter = new XMLWriter(os);
                    xmlWriter.write(mergedPacksLang);
                    os.close();
                    os = null;

                    // getting the URL to the new merged file
                    mergedPackLangFileURL = mergedPackLangFile.toURL();
                }

                compiler.addResource(id, mergedPackLangFileURL);
            }
        }
        catch (Exception e) {
            throw new CompilerException("Unable to merge multiple packsLang.xml files: "
                    + e.getMessage(), e);
        }
        finally {
            if (null != os) {
                try {
                    os.close();
                }
                catch (IOException e) {
                    // ignore as there is nothing we can realistically do
                    // so lets at least try to close the input stream
                }
            }
        }
    }

    /**
     * @param xmlPanel
     * @param panel
     * @throws CompilerException
     */
    private void addPanelActions(IXMLElement xmlPanel, Panel panel) throws CompilerException {
        IXMLElement xmlActions = xmlPanel.getFirstChildNamed(PanelAction.PANEL_ACTIONS_TAG);
        if (xmlActions != null) {
            Vector<IXMLElement> actionList = xmlActions
                    .getChildrenNamed(PanelAction.PANEL_ACTION_TAG);
            if (actionList != null) {
                for (IXMLElement action : actionList) {
                    String stage = action.getAttribute(PanelAction.PANEL_ACTION_STAGE_TAG);
                    String actionName = action.getAttribute(PanelAction.PANEL_ACTION_CLASSNAME_TAG);
                    if (actionName != null) {
                        Vector<IXMLElement> params = action.getChildrenNamed("param");
                        PanelActionConfiguration config = new PanelActionConfiguration();

                        for (IXMLElement param : params) {
                            IXMLElement keyElement = param.getFirstChildNamed("key");
                            IXMLElement valueElement = param.getFirstChildNamed("value");
                            if ((keyElement != null) && (valueElement != null)) {
                                Debug.trace("Adding configuration property " + keyElement.getContent() + " with value " + valueElement.getContent() + " for action " + actionName);
                                config.addProperty(keyElement.getContent(), valueElement.getContent());
                            }
                        }
                        panel.putPanelActionConfiguration(actionName, config);
                    }
                    try {
                        ActionStage actionStage = ActionStage.valueOf(stage);
                        switch (actionStage) {
                            case preconstruct:
                                panel.addPreConstructionActions(actionName);
                                break;
                            case preactivate:
                                panel.addPreActivationAction(actionName);
                                break;
                            case prevalidate:
                                panel.addPreValidationAction(actionName);
                                break;
                            case postvalidate:
                                panel.addPostValidationAction(actionName);
                                break;
                        }
                    }
                    catch (IllegalArgumentException e) {
                        parseError(action, "Invalid value [" + stage + "] for attribute : "
                                + PanelAction.PANEL_ACTION_STAGE_TAG);
                    }
                }
            } else {
                parseError(xmlActions, "<" + PanelAction.PANEL_ACTIONS_TAG + "> requires a <"
                        + PanelAction.PANEL_ACTION_TAG + ">");
            }
        }
    }

}