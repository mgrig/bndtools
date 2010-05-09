package bndtools.launch;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Map;
import java.util.Properties;
import java.util.Map.Entry;
import java.util.logging.Level;

import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.launching.JavaLaunchDelegate;

import aQute.bnd.build.Container;
import aQute.bnd.build.Project;
import aQute.bnd.build.Container.TYPE;
import aQute.bnd.plugin.Activator;
import aQute.bnd.plugin.Central;
import aQute.lib.osgi.Builder;
import aQute.lib.osgi.Constants;
import aQute.lib.osgi.Processor;
import aQute.libg.header.OSGiHeader;
import bndtools.BndConstants;
import bndtools.Plugin;

public class OSGiLaunchDelegate extends JavaLaunchDelegate {

    private static final String LAUNCHER_BSN = "bndtools.launcher";
    private static final String LAUNCHER_MAIN_CLASS = LAUNCHER_BSN + ".Main";

    private static final String EMPTY = "";
    private static final String ANY_VERSION = "0"; //$NON-NLS-1$

    protected File launchPropsFile;
    protected boolean enableDebugOption = false;

    @Override
    public void launch(final ILaunchConfiguration configuration, String mode, final ILaunch launch, IProgressMonitor monitor) throws CoreException {
        Properties launchProps = generateLaunchProperties(configuration);
        saveLaunchPropsFile(launchProps);

        checkDebugOption(configuration);

        registerLaunchPropertiesRegenerator(configuration, launch);

        super.launch(configuration, mode, launch, monitor);
    }

    /**
     * Generates the initial launch properties.
     * @param configuration
     * @throws CoreException
     */
    protected Properties generateLaunchProperties(ILaunchConfiguration configuration) throws CoreException {
        Project model = getBndProject(configuration);
        Properties outputProps = new Properties();

        // Expand -runbundles
        Collection<String> runBundlePaths = calculateRunBundlePaths(model);
        outputProps.put(LaunchConstants.PROP_LAUNCH_RUNBUNDLES, Processor.join(runBundlePaths));

        // Copy misc properties
        copyConfigurationToLaunchFile(configuration, model, outputProps);

        return outputProps;
    }

    /**
     * Saves the launch properties into the file location indicated by the {@link #launchPropsFile} field.
     * @param props
     * @throws CoreException
     */
    protected void saveLaunchPropsFile(Properties props) throws CoreException {
        try {
            if(launchPropsFile == null) {
                launchPropsFile = File.createTempFile("bndtools.launcher", ".properties");
                launchPropsFile.deleteOnExit();
            }
        } catch (IOException e) {
            throw new CoreException(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, "Error creating temporary launch properties file.", e));
        }

        // Write out properties file
        FileOutputStream out = null;
        try {
            System.out.println("SAVING: " + launchPropsFile.getAbsolutePath());
            out = new FileOutputStream(launchPropsFile);
            props.store(out, "Generated by Bndtools IDE");
        } catch (IOException e) {
            throw new CoreException(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, "Error creating temporary launch properties file.", e));
        } finally {
            try {
                if(out != null) out.close();
            } catch (IOException e) {
            }
        }
    }

    protected void checkDebugOption(ILaunchConfiguration configuration) throws CoreException {
        // Check whether to enable launch debugging
        Level logLevel = Level.parse(configuration.getAttribute(LaunchConstants.ATTR_LOGLEVEL, LaunchConstants.DEFAULT_LOGLEVEL));
        enableDebugOption = logLevel.intValue() <= Level.FINE.intValue();
    }

    /**
     * Copies additional properties from both the launch configuration and the
     * project model itself into the launch properties file.
     *
     * @param configuration
     *            The launch configuration
     * @param model
     *            The project model
     * @param outputProps
     *            The output properties, which will be written to the launch
     *            properties file.
     * @throws CoreException
     */
    protected void copyConfigurationToLaunchFile(ILaunchConfiguration configuration, Project model, Properties outputProps) throws CoreException {
        outputProps.setProperty(LaunchConstants.PROP_LAUNCH_DYNAMIC_BUNDLES,
                Boolean.toString(configuration.getAttribute(LaunchConstants.ATTR_DYNAMIC_BUNDLES, LaunchConstants.DEFAULT_DYNAMIC_BUNDLES)));

        outputProps.setProperty(LaunchConstants.PROP_LAUNCH_LOGLEVEL,
                configuration.getAttribute(LaunchConstants.ATTR_LOGLEVEL, LaunchConstants.DEFAULT_LOGLEVEL));

        outputProps.setProperty(LaunchConstants.PROP_LAUNCH_LOG_OUTPUT,
                configuration.getAttribute(LaunchConstants.ATTR_LOG_OUTPUT, LaunchConstants.DEFAULT_LOG_OUTPUT));

        outputProps.setProperty(LaunchConstants.PROP_LAUNCH_CLEAN,
                Boolean.toString(configuration.getAttribute(LaunchConstants.ATTR_CLEAN, LaunchConstants.DEFAULT_CLEAN)));

        // Copy the -runproperties values
        Map<String, String> runProps = OSGiHeader.parseProperties(model.getProperties().getProperty(Constants.RUNPROPERTIES, ""));
        for (Entry<String, String> entry : runProps.entrySet()) {
            String value = entry.getValue() != null
                ? entry.getValue()
                : "";
            outputProps.setProperty(entry.getKey(), value);
        }
    }

    /**
     * Calculates the full paths to the set of runtime bundles. Used to expand
     * the launch properties file.
     *
     * @param model
     *            The project model
     * @return A collection of absolute file paths
     * @throws CoreException
     */
    protected Collection<String> calculateRunBundlePaths(Project model) throws CoreException {
        Collection<String> runBundlePaths = new LinkedList<String>();
        synchronized (model) {
            try {
                // Calculate physical paths for -runbundles from bnd.bnd
                Collection<Container> runbundles = model.getRunbundles();
                MultiStatus resolveErrors = new MultiStatus(Plugin.PLUGIN_ID, 0, "One or more run bundles could not be resolved.", null);
                for (Container container : runbundles) {
                    if (container.getType() == TYPE.ERROR) {
                        resolveErrors.add(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, MessageFormat.format("Could not resolve run bundle {0}, version {1}.",
                                container.getBundleSymbolicName(), container.getVersion()), null));
                    } else {
                        runBundlePaths.add(container.getFile().getAbsolutePath());
                    }
                }
                if (!resolveErrors.isOK()) {
                    throw new CoreException(resolveErrors);
                }

                // Add the project's own output bundles
                Collection<? extends Builder> builders = model.getSubBuilders();
                for (Builder builder : builders) {
                    File bundlefile = new File(model.getTarget(), builder.getBsn() + ".jar");
                    runBundlePaths.add(bundlefile.getAbsolutePath());
                }
            } catch (Exception e) {
                throw new CoreException(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, "Error finding run bundles.", e));
            }
        }
        return runBundlePaths;
    }

    /**
     * Registers a resource listener with the project model file ({@code bnd.bnd}) to
     * update the project launcher properties file when the model changes. This
     * is used to dynamically change the set of runtime bundles. The resource
     * listener is automatically unregistered when the launched process
     * terminates.
     *
     * @param configuration
     * @param launch
     * @throws CoreException
     */
    protected void registerLaunchPropertiesRegenerator(final ILaunchConfiguration configuration, final ILaunch launch) throws CoreException {
        final Project model = getBndProject(configuration);

        final IPath propsPath = Central.toPath(model, model.getPropertiesFile());
        final IResourceChangeListener resourceListener = new IResourceChangeListener() {
            public void resourceChanged(IResourceChangeEvent event) {
                try {
                    IResourceDelta delta = event.getDelta();
                    delta = delta.findMember(propsPath);
                    if (delta != null) {
                        if (delta.getKind() == IResourceDelta.CHANGED) {
                            Properties launchProps = generateLaunchProperties(configuration);
                            saveLaunchPropsFile(launchProps);
                        } else if (delta.getKind() == IResourceDelta.REMOVED && launchPropsFile != null) {
                            launchPropsFile.delete();
                        }
                    }
                } catch (Exception e) {
                    IStatus status = new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, "Error updating launch properties file.", e);
                    Plugin.log(status);
                }
            }
        };
        ResourcesPlugin.getWorkspace().addResourceChangeListener(resourceListener);

        // Register a listener for termination of the launched process
        Runnable onTerminate = new Runnable() {
            public void run() {
                System.out.println("Processes terminated.");
                ResourcesPlugin.getWorkspace().removeResourceChangeListener(resourceListener);
            }
        };
        DebugPlugin.getDefault().addDebugEventListener(new TerminationListener(launch, onTerminate));
    }

    @Override
    public String getMainTypeName(ILaunchConfiguration configuration) throws CoreException {
        return LAUNCHER_MAIN_CLASS;
    }

    @Override
    public String getProgramArguments(ILaunchConfiguration configuration) throws CoreException {
        String args = launchPropsFile.getAbsolutePath();
        if(enableDebugOption) {
            args += " --debug";
        }
        return args;
    }

    protected Project getBndProject(ILaunchConfiguration configuration) throws CoreException {
        IJavaProject javaProject = getJavaProject(configuration);
        Project model = Activator.getDefault().getCentral().getModel(javaProject);
        return model;
    }

    @Override
    public String[] getClasspath(ILaunchConfiguration configuration) throws CoreException {
        Project model = getBndProject(configuration);

        // Get the framework bundle
        File fwkBundle = findFramework(model);

        // Get the launcher bundle
        File launcherBundle = findBundle(model, LAUNCHER_BSN, ANY_VERSION);
        if (launcherBundle == null) {
            throw new CoreException(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, MessageFormat.format("Could not find launcher bundle {0}.", LAUNCHER_BSN), null));
        }

        // Set the classpath
        String[] classpath = new String[2];
        classpath[0] = launcherBundle.getAbsolutePath();
        classpath[1] = fwkBundle.getAbsolutePath();

        return classpath;
    }

    protected File findFramework(Project model) throws CoreException {
        String frameworkSpec = model.getProperty(BndConstants.RUNFRAMEWORK, EMPTY);
        if(frameworkSpec == null || frameworkSpec.length() == 0) {
            throw new CoreException(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, MessageFormat.format("No OSGi framework was specified in {0}.", model.getPropertiesFile().getAbsolutePath()), null));
        }
        Map<String, Map<String, String>> fwkHeader = OSGiHeader.parseHeader(frameworkSpec);
        if(fwkHeader.size() != 1) {
            throw new CoreException(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, "Invalid format for OSGi framework specification.", null));
        }
        Entry<String, Map<String, String>> fwkHeaderEntry = fwkHeader.entrySet().iterator().next();
        String fwkBSN = fwkHeaderEntry.getKey();

        String fwkVersion = fwkHeaderEntry.getValue().get(Constants.VERSION_ATTRIBUTE);
        if(fwkVersion == null)
            fwkVersion = ANY_VERSION;

        File fwkBundle = findBundle(model, fwkBSN, fwkVersion);
        if (fwkBundle == null) {
            throw new CoreException(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, MessageFormat.format("Could not find framework {0}, version {1}.", fwkBSN, fwkVersion), null));
        }
        return fwkBundle;
    }

    protected File findBundle(Project project, String bsn, String version) throws CoreException {
        try {
            Container snapshotContainer = project.getBundle(bsn, "snapshot", Constants.STRATEGY_HIGHEST, null);
            if (snapshotContainer != null && snapshotContainer.getType() != TYPE.ERROR) {
                return snapshotContainer.getFile();
            }

            Container repoContainer = project.getBundle(bsn, version, Constants.STRATEGY_HIGHEST, null);
            if (repoContainer != null && repoContainer.getType() != TYPE.ERROR) {
                return repoContainer.getFile();
            }
        } catch (Exception e) {
            throw new CoreException(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, MessageFormat.format(
                    "An error occurred while searching the workspace or repositories for bundle {0}.", bsn), e));
        }
        return null;
    }
}