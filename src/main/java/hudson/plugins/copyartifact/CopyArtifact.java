/*
 * The MIT License
 *
 * Copyright (c) 2004-2011, Sun Microsystems, Inc., Alan Harder
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.copyartifact;

import com.thoughtworks.xstream.converters.UnmarshallingContext;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.console.HyperlinkNote;
import hudson.diagnosis.OldDataMonitor;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.matrix.MatrixBuild;
import hudson.matrix.MatrixProject;
import hudson.maven.MavenModuleSet;
import hudson.maven.MavenModuleSetBuild;
import hudson.model.*;
import hudson.model.listeners.ItemListener;
import hudson.plugins.copyartifact.VirtualFileScanner.VirtualFileWithPathInfo;
import hudson.security.ACL;
import hudson.security.SecurityRealm;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.DescribableList;
import hudson.util.FormValidation;
import hudson.util.VariableResolver;
import hudson.util.XStream2;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import jenkins.model.ArtifactManager;
import jenkins.model.Jenkins;

import jenkins.tasks.SimpleBuildStep;
import jenkins.util.VirtualFile;

import org.acegisecurity.Authentication;
import org.acegisecurity.GrantedAuthority;
import org.acegisecurity.providers.UsernamePasswordAuthenticationToken;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Build step to copy artifacts from another project.
 * @author Alan Harder
 */
public class CopyArtifact extends Builder implements SimpleBuildStep {

    // specifies upgradeCopyArtifact is needed to work.
    private static boolean upgradeNeeded = false;
    private static Logger LOGGER = Logger.getLogger(CopyArtifact.class.getName());
    private static final BuildSelector DEFAULT_BUILD_SELECTOR = new StatusBuildSelector(true);

    /**
     * The result of picking the build to copy from.
     */
    public static class CopyArtifactPickResult {
        public static enum Result {
            /**
             * a build is found.
             */
            Found,
            /**
             * no project (or job) is found.
             */
            ProjectNotFound,
            /**
             * a project is found but no build is found.
             */
            BuildNotFound,
        };
        
        @Nonnull
        public final Result result;
        
        @CheckForNull
        public final Job<?, ?> job;
        
        @CheckForNull
        public final Run<?, ?> build;
        
        private CopyArtifactPickResult(Result result, Job<?, ?> job, Run<?, ?> build) {
            this.result = result;
            this.job = job;
            this.build = build;
        }
        
        private static CopyArtifactPickResult found(Run<?, ?> run) {
            return new CopyArtifactPickResult(
                    Result.Found,
                    run.getParent(),
                    run
            );
        }
        
        private static CopyArtifactPickResult projectNotFound() {
            return new CopyArtifactPickResult(
                    Result.ProjectNotFound,
                    null,
                    null
            );
        }
        
        private static CopyArtifactPickResult buildNotFound(Job<?, ?> job) {
            return new CopyArtifactPickResult(
                    Result.BuildNotFound,
                    job,
                    null
            );
        }
    };

    /**
     * The result of copying artifacts.
     */
    public static enum CopyArtifactCopyResult {
        /**
         * No files to copy.
         */
        NoFileToCopy    (0),
        /**
         * Copied one or more files.
         */
        Copied          (1),
        ;
        
        private int numeric;
        private CopyArtifactCopyResult(int numeric) {
            this.numeric = numeric;
        }
        
        private static CopyArtifactCopyResult byNumber(int numeric) {
            for (CopyArtifactCopyResult v : CopyArtifactCopyResult.values()) {
                if (v.numeric == numeric) {
                    return v;
                }
            }
            return null;
        }
        
        public CopyArtifactCopyResult merge(CopyArtifactCopyResult valueToMerge) {
            return CopyArtifactCopyResult.byNumber(Math.max(numeric, valueToMerge.numeric));
        }
    }

    @Deprecated private String projectName;
    private String project;
    @Deprecated private String parameters;
    private String filter, target;
    private String excludes;
    private /*almost final*/ BuildSelector selector;
    @Deprecated private transient Boolean stable;
    private Boolean flatten, optional;
    private boolean doNotFingerprintArtifacts;
    private String resultVariableSuffix;
    private boolean verbose;
    private BuildFilter buildFilter;

    @Deprecated
    public CopyArtifact(String projectName, String parameters, BuildSelector selector, String filter, String target,
            boolean flatten, boolean optional) {
        this(projectName, parameters, selector, filter, target, flatten, optional, true);
    }

    @Deprecated
    public CopyArtifact(String projectName, String parameters, BuildSelector selector, String filter, String target,
            boolean flatten, boolean optional, boolean fingerprintArtifacts) {
        this(projectName, parameters, selector, filter, null, target, flatten, optional, fingerprintArtifacts);
    }

    @Deprecated
    public CopyArtifact(String projectName, String parameters, BuildSelector selector, String filter, String excludes, String target,
                        boolean flatten, boolean optional, boolean fingerprintArtifacts) {
        this(projectName);
        setParameters(parameters);
        setFilter(filter);
        setTarget(target);
        setExcludes(excludes);
        if (selector == null) {
            selector = DEFAULT_BUILD_SELECTOR;
        }
        setSelector(selector);
        setFlatten(flatten);
        setOptional(optional);
        setFingerprintArtifacts(fingerprintArtifacts);
    }

    @DataBoundConstructor
    public CopyArtifact(String projectName) {
        this.project = projectName;

        // Apply defaults to all other properties.
        setFilter(null);
        setTarget(null);
        setExcludes(null);
        setSelector(DEFAULT_BUILD_SELECTOR);
        setFlatten(false);
        setOptional(false);
        setFingerprintArtifacts(false);
        setResultVariableSuffix(null);
        setBuildFilter(null);
    }

    /**
     * @param parameters
     * @deprecated use {@link #setBuildFilter(BuildFilter)} and {@link ParametersBuildFilter} instead.
     */
    @Deprecated
    public void setParameters(String parameters) {
        parameters = Util.fixEmptyAndTrim(parameters);
        buildFilter = (parameters != null)?new ParametersBuildFilter(parameters):null;
    }

    @DataBoundSetter
    public void setFilter(String filter) {
        this.filter = Util.fixNull(filter).trim();
    }

    @DataBoundSetter
    public void setTarget(String target) {
        this.target = Util.fixNull(target).trim();
    }

    @DataBoundSetter
    public void setExcludes(String excludes) {
        this.excludes = Util.fixNull(excludes).trim();
    }

    @DataBoundSetter
    public void setSelector(@Nonnull BuildSelector selector) {
        this.selector = selector;
    }

    @DataBoundSetter
    public void setFlatten(boolean flatten) {
        this.flatten = flatten ? Boolean.TRUE : null;
    }

    @DataBoundSetter
    public void setOptional(boolean optional) {
        this.optional = optional ? Boolean.TRUE : null;
    }

    @DataBoundSetter
    public void setFingerprintArtifacts(boolean fingerprintArtifacts) {
        this.doNotFingerprintArtifacts = !fingerprintArtifacts;
    }

    /**
     * Set the suffix for variables to store copying results.
     * 
     * @param resultVariableSuffix
     */
    @DataBoundSetter
    public void setResultVariableSuffix(String resultVariableSuffix) {
        this.resultVariableSuffix = Util.fixEmptyAndTrim(resultVariableSuffix);
    }

    /**
     * @param verbose
     * @since 2.0
     */
    @DataBoundSetter
    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    /**
     * @param buildFilter
     * @since 2.0
     */
    @DataBoundSetter
    public void setBuildFilter(@CheckForNull BuildFilter buildFilter) {
        this.buildFilter = buildFilter;
    }

    // Upgrade data from old format
    public static class ConverterImpl extends XStream2.PassthruConverter<CopyArtifact> {
        public ConverterImpl(XStream2 xstream) { super(xstream); }
        @Override protected void callback(CopyArtifact obj, UnmarshallingContext context) {
            if (obj.selector == null) {
                obj.selector = new StatusBuildSelector(obj.stable != null && obj.stable);
                OldDataMonitor.report(context, "1.355"); // Core version# when CopyArtifact 1.2 released
            }
            if (obj.isUpgradeNeeded()) {
                // A Copy Artifact to be upgraded.
                // For information of the containing project is needed, 
                // The upgrade will be performed by upgradeCopyArtifact.
                setUpgradeNeeded();
            }
        }
    }

    private static synchronized void setUpgradeNeeded() {
        if (!upgradeNeeded) {
            LOGGER.info("Upgrade for Copy Artifact is scheduled.");
            upgradeNeeded = true;
        }
    }

    // get all CopyArtifacts configured to AbstractProject. This works both for Project and MatrixProject.
    private static List<CopyArtifact> getCopyArtifactsInProject(AbstractProject<?,?> project) throws IOException {
        DescribableList<Builder,Descriptor<Builder>> list =
                project instanceof Project ? ((Project<?,?>)project).getBuildersList()
                  : (project instanceof MatrixProject ?
                      ((MatrixProject)project).getBuildersList() : null);
        if (list == null) return Collections.emptyList();
        return list.getAll(CopyArtifact.class);
    }

    @Initializer(after=InitMilestone.JOB_LOADED)
    public static void upgradeCopyArtifact() {
        if (!upgradeNeeded) {
            return;
        }
        Jenkins jenkins = Jenkins.getInstance();
        if (jenkins == null) {
            LOGGER.log(Level.SEVERE, "Called for initializing, but Jenkins instance is unavailable.");
            return;
        }
        upgradeNeeded = false;
        
        boolean isUpgraded = false;
        for (AbstractProject<?,?> project: jenkins.getAllItems(AbstractProject.class)) {
            try {
                for (CopyArtifact target: getCopyArtifactsInProject(project)) {
                    try {
                        if (target.upgradeIfNecessary(project)) {
                            isUpgraded = true;
                        }
                    } catch(IOException e) {
                        LOGGER.log(Level.SEVERE, String.format("Failed to upgrade CopyArtifact in %s", project.getFullName()), e);
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, String.format("Failed to upgrade CopyArtifact in %s", project.getFullName()), e);
            }
        }
        
        if (!isUpgraded) {
            // No CopyArtifact is upgraded.
            LOGGER.warning("Update of CopyArtifact is scheduled, but no CopyArtifact to upgrade was found!");
        }
    }

    public String getProjectName() {
        return project;
    }
    
    /**
     * @return
     * @deprecated use {@link #getBuildFilter()} instead.
     */
    @Deprecated
    public String getParameters() {
        return parameters;
    }

    @Deprecated
    public BuildSelector getBuildSelector() {
        return selector;
    }

    public BuildSelector getSelector() {
        return selector;
    }

    public String getFilter() {
        return filter;
    }

    public String getExcludes() {
        return excludes;
    }

    public String getTarget() {
        return target;
    }

    public boolean isFlatten() {
        return flatten != null && flatten;
    }

    public boolean isOptional() {
        return optional != null && optional;
    }

    /**
     * @return the suffix for variables to store copying results.
     */
    public String getResultVariableSuffix() {
        return resultVariableSuffix;
    }

    /**
     * @return whether output logs for diagnostics.
     * @since 2.0
     */
    public boolean isVerbose() {
        return verbose;
    }

    /**
     * @return the filter for builds.
     * @since 2.0
     */
    @CheckForNull
    public BuildFilter getBuildFilter() {
        return buildFilter;
    }

    private boolean upgradeIfNecessary(AbstractProject<?,?> job) throws IOException {
        if (isUpgradeNeeded()) {
            Jenkins jenkins = Jenkins.getInstance();
            if (jenkins == null) {
                LOGGER.log(Level.SEVERE, "upgrading copyartifact is required for {0} but Jenkins instance is unavailable", job.getDisplayName());
                return false;
            }
            int i = projectName.lastIndexOf('/');
            if (i != -1 && projectName.indexOf('=', i) != -1 && /* not matrix */jenkins.getItem(projectName, job.getParent(), Job.class) == null) {
                project = projectName.substring(0, i);
                parameters = projectName.substring(i + 1);
            } else {
                project = projectName;
                parameters = null;
            }
            LOGGER.log(Level.INFO, "Split {0} into {1} with parameters {2}", new Object[] {projectName, project, parameters});
            projectName = null;
            job.save();
            return true;
        } else {
            return false;
        }
    }

    private boolean isUpgradeNeeded() {
        return (projectName != null);
    }

    public boolean isFingerprintArtifacts() {
        return !doNotFingerprintArtifacts;
    }

    @Override
    public void perform(@Nonnull Run<?, ?> build, @Nonnull FilePath workspace, @Nonnull Launcher launcher, @Nonnull TaskListener listener) throws InterruptedException, IOException {
        Jenkins jenkins = Jenkins.getInstance();
        if (jenkins == null) {
            throw new AbortException("Jenkins instance is unavailable.");
        }
        if (build instanceof AbstractBuild) {
            upgradeIfNecessary(((AbstractBuild)build).getProject());
        }

        EnvVars env = build.getEnvironment(listener);
        if (build instanceof AbstractBuild) {
            env.overrideAll(((AbstractBuild)build).getBuildVariables()); // Add in matrix axes..
        } else {
            // Abstract#getEnvironment(TaskListener) put build parameters to
            // environments, but Run#getEnvironment(TaskListener) doesn't.
            // That means we can't retrieve build parameters from WorkflowRun
            // as it is a subclass of Run, not of AbstractBuild.
            // We need expand build parameters manually.
            // See JENKINS-26694, JENKINS-30357 for details.
            for(ParametersAction pa: build.getActions(ParametersAction.class)) {
                // We have to extract parameters manally as ParametersAction#buildEnvVars
                // (overrides EnvironmentContributingAction#buildEnvVars)
                // is applicable only for AbstractBuild.
                for(ParameterValue pv: pa.getParameters()) {
                    pv.buildEnvironment(build, env);
                }
            }
        }

        CopyArtifactPickContext pickContext = new CopyArtifactPickContext();
        pickContext.setJenkins(jenkins);
        pickContext.setCopierBuild(build);
        pickContext.setListener(listener);
        pickContext.setEnvVars(env);
        pickContext.setVerbose(isVerbose());
        
        String jobName = env.expand(getProjectName());
        pickContext.setProjectName(jobName);
        pickContext.setBuildFilter(getBuildFilter());

        CopyArtifactPickResult pick = pickBuildToCopyFrom(getSelector(), pickContext);
        switch(pick.result) {
            case ProjectNotFound:
            {
                throw new AbortException(Messages.CopyArtifact_MissingProject(jobName));
            }
            case BuildNotFound:
            {
                String message = Messages.CopyArtifact_MissingBuild(jobName);
                if (isOptional()) {
                    // just return without an error
                    pickContext.logInfo(message);
                    return;
                } else {
                    // Fail build if copy is not optional
                    throw new AbortException(message);
                }
            }
            case Found:
            {
                // nothing to do.
                break;
            }
        }

        // Add info about the selected build into the environment
        EnvAction envData = build.getAction(EnvAction.class);
        if (envData == null) {
            envData = new EnvAction();
            build.addAction(envData);
        }
        envData.add(build, pick.build, jobName, getResultVariableSuffix());
        
        CopyArtifactCopyContext copyContext = new CopyArtifactCopyContext();
        copyContext.setJenkins(jenkins);
        copyContext.setCopierBuild(build);
        copyContext.setListener(listener);
        copyContext.setEnvVars(env);
        copyContext.setVerbose(isVerbose());
        copyContext.setFlatten(isFlatten());
        copyContext.setFingerprintArtifacts(isFingerprintArtifacts());

        FilePath targetBaseDir = workspace;
        String targetDirPath = "";
        if (!StringUtils.isEmpty(getTarget())) {
            targetDirPath = env.expand(getTarget());
        }
        String expandedFilter = env.expand(getFilter());
        if (StringUtils.isBlank(expandedFilter)) {
            expandedFilter = "**";
        }
        String expandedExcludes = env.expand(getExcludes());
        if (StringUtils.isBlank(expandedExcludes)) {
            expandedExcludes = null;
        }
        copyContext.setTargetBaseDir(targetBaseDir);
        copyContext.setTargetDirPath(targetDirPath);
        copyContext.setIncludes(expandedFilter);
        copyContext.setExcludes(expandedExcludes);
        copyContext.setCopier(jenkins.getExtensionList(Copier.class).get(0));
        
        
        switch (copyArttifactsFrom(pick.build, copyContext)) {
        case NoFileToCopy:
            if (!isOptional()) {
                throw new AbortException(Messages.CopyArtifact_FailedToCopy(jobName, expandedFilter));
            }
            // fall through
        case Copied:
            // nothing to do
            break;
        }
        
    }

    /**
     * @param selector
     * @param context
     * @return
     * @since 2.0
     */
    public static CopyArtifactPickResult pickBuildToCopyFrom(BuildSelector selector, CopyArtifactPickContext context) {
        Job<?, ?> job = context.getJenkins().getItem(
                context.getProjectName(),
                getItemGroup(context.getCopierBuild()),
                Job.class
        );
        if (job != null && !canReadFrom(job, context.getCopierBuild())) {
            job = null; // Disallow access
        }
        if (job == null) {
            return CopyArtifactPickResult.projectNotFound();
        }
        
        Run<?,?> src = selector.pickBuildToCopyFrom(job, context);
        if (src == null) {
            return CopyArtifactPickResult.buildNotFound(job);
        }
        
        return CopyArtifactPickResult.found(src);
    }
    
    private static boolean canReadFrom(Job<?, ?> job, Run<?, ?> build) {
        Job<?, ?> fromJob = job;
        Job<?, ?> toJob = build.getParent();

        if (CopyArtifactPermissionProperty.canCopyArtifact(getRootProject(toJob), getRootProject(fromJob))) {
            return true;
        }

        Authentication a = Jenkins.getAuthentication();
        if (!ACL.SYSTEM.equals(a)) {
            // if the build does not run on SYSTEM authorization,
            // Jenkins is configured to use QueueItemAuthenticator.
            // In this case, builds are configured to run with a proper authorization
            // (for example, builds run with the authorization of the user who triggered the build),
            // and we should check access permission with that authorization.
            // QueueItemAuthenticator is available from Jenkins 1.520.
            // See also JENKINS-14999, JENKINS-16956, JENKINS-18285.
            boolean b = job.getACL().hasPermission(Item.READ);
            if (!b)
                LOGGER.fine(String.format("Refusing to copy artifact from %s to %s because %s lacks Item.READ access",job,build, a));
            return b;
        }
        
        // for the backward compatibility, 
        // test the permission as an anonymous authenticated user.
        boolean b = job.getACL().hasPermission(
                new UsernamePasswordAuthenticationToken("authenticated", "",
                        new GrantedAuthority[]{ SecurityRealm.AUTHENTICATED_AUTHORITY }),
                Item.READ);
        if (!b)
            LOGGER.fine(String.format("Refusing to copy artifact from %s to %s because 'authenticated' lacks Item.READ access",job,build));
        return b;
    }

    private static Job<?, ?> getRootProject(Job<?, ?> job) {
        if (job instanceof AbstractProject) {
            return ((AbstractProject<?,?>)job).getRootProject();
        } else {
            return job;
        }
    }

    // retrieve the "folder" (jenkins root if no folder used) for this build
    private static ItemGroup getItemGroup(Run<?, ?> build) {
        return getRootProject(build.getParent()).getParent();
    }


    /**
     * @param src
     * @param context
     * @return
     * @throws IOException
     * @throws InterruptedException
     * @since 2.0
     */
    public static CopyArtifactCopyResult copyArttifactsFrom(Run<?,?> src, CopyArtifactCopyContext context)
            throws IOException, InterruptedException
    {
        
        if (context.getJenkins().getPlugin("maven-plugin") != null && (src instanceof MavenModuleSetBuild) ) {
        // use classes in the "maven-plugin" plugin as might not be installed
            // Copy artifacts from the build (ArchiveArtifacts build step)
            CopyArtifactCopyResult copyResult = copyArtifactsFromDirect(src, context);
            
            // Copy artifacts from all modules of this Maven build (automatic archiving)
            for (Run<?, ?> r : ((MavenModuleSetBuild)src).getModuleLastBuilds().values()) {
                copyResult = copyResult.merge(copyArtifactsFromDirect(r, context));
            }
            
            return copyResult;
        } else if (src instanceof MatrixBuild) {
            CopyArtifactCopyResult copyResult = CopyArtifactCopyResult.NoFileToCopy;
            
            // Copy artifacts from all configurations of this matrix build
            // Use MatrixBuild.getExactRuns if available
            for (Run r : ((MatrixBuild) src).getExactRuns()) {
                // Use subdir of targetDir with configuration name (like "jdk=java6u20")
                CopyArtifactCopyContext contextForChild = (CopyArtifactCopyContext) context.clone();
                contextForChild.setTargetBaseDir(context.getTargetBaseDir().child(r.getParent().getName()));
                copyResult = copyResult.merge(copyArtifactsFromDirect(r, contextForChild));
            }
            
            return copyResult;
        }
        
        return copyArtifactsFromDirect(src, context);
    }

    private static CopyArtifactCopyResult copyArtifactsFromDirect(Run<?, ?> src, CopyArtifactCopyContext context)
            throws IOException, InterruptedException {
        context.logDebug("Copying artifacts from {0}", src.getFullDisplayName());
        context.getTargetDir().mkdirs();
        
        ArtifactManager manager = src.getArtifactManager();
        VirtualFile srcDir = manager.root();
        if (srcDir == null || !srcDir.exists()) {
            context.logDebug("No artifacts to copy");
            return CopyArtifactCopyResult.NoFileToCopy;
        }

        context.getCopier().init(src, context);
        try {
            VirtualFileScanner scanner = new VirtualFileScanner(
                    context.getIncludes(),
                    context.getExcludes(),
                    false       // useDefaultExcludes
            );
            List<VirtualFileWithPathInfo> fileList = scanner.scanFile(srcDir);
            if (!context.isFlatten()) {
                for (VirtualFileWithPathInfo file : fileList) {
                    FilePath path = context.getTargetDir();
                    for (String fragment : file.pathFragments) {
                        path = new FilePath(path, fragment);
                    }
                    context.logDebug("Copying to {0}", path);
                    context.getCopier().copy(
                            file.file,
                            path,
                            context
                    );
                }
            } else {
                for (VirtualFileWithPathInfo file : fileList) {
                    FilePath path = new FilePath(context.getTargetDir(), file.file.getName());
                    context.logDebug("Copying to {0}", path);
                    context.getCopier().copy(
                            file.file,
                            path,
                            context
                    );
                }
            }
            
            int cnt = fileList.size();

            context.logInfo(Messages.CopyArtifact_Copied(
                    cnt,
                    HyperlinkNote.encodeTo('/'+ src.getParent().getUrl(), src.getParent().getFullDisplayName()),
                    HyperlinkNote.encodeTo('/'+src.getUrl(), Integer.toString(src.getNumber()))
            ));
            return (cnt > 0)?CopyArtifactCopyResult.Copied:CopyArtifactCopyResult.NoFileToCopy;
        } finally {
            context.getCopier().end(context);
        }
    }

    /**
     * Tests whether specified variable name is valid.
     * Package scope for testing purpose.
     * 
     * @param variableName
     * @return
     */
    static boolean isValidVariableName(final String variableName) {
        if(StringUtils.isBlank(variableName)) {
            return false;
        }
        
        // The pattern for variables are defined in hudson.Util.VARIABLE.
        // It's not exposed unfortunately and tests the variable
        // by actually expanding that.
        final String expected = "GOOD";
        String expanded = Util.replaceMacro(
            String.format("${%s}", variableName),
            new VariableResolver<String>() {
                @Override
                public String resolve(String name) {
                    if(variableName.equals(name)) {
                        return expected;
                    }
                    return null;
                }
            }
        );
        
        return expected.equals(expanded);
    }
    
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public FormValidation doCheckProjectName(
                @AncestorInPath Job<?,?> anc, @QueryParameter String value) {
            // Require CONFIGURE permission on this project
            if (!anc.hasPermission(Item.CONFIGURE)) return FormValidation.ok();
            
            Jenkins jenkins = Jenkins.getInstance();
            if (jenkins == null) {
                // validation is useless if Jenkins is no longer available.
                return FormValidation.ok();
            }
            FormValidation result;
            Item item = jenkins.getItem(value, anc.getParent());
            if (item != null)
                if (jenkins.getPlugin("maven-plugin") != null && item instanceof MavenModuleSet) {
                    result = FormValidation.warning(Messages.CopyArtifact_MavenProject());
                } else {
                    result = (item instanceof MatrixProject)
                          ? FormValidation.warning(Messages.CopyArtifact_MatrixProject())
                          : FormValidation.ok();
                }
            else if (value.indexOf('$') >= 0)
                result = FormValidation.warning(Messages.CopyArtifact_ParameterizedName());
            else {
                Job<?,?> nearest = Items.findNearest(Job.class, value, anc.getParent());
                if (nearest != null) {
                result = FormValidation.error(
                    hudson.tasks.Messages.BuildTrigger_NoSuchProject(
                        value, nearest.getName()));
                } else {
                    result = FormValidation.error(hudson.tasks.Messages.BuildTrigger_NoProjectSpecified());
                }
            }
            return result;
        }

        public FormValidation doCheckResultVariableSuffix(@QueryParameter String value) {
            value = Util.fixEmptyAndTrim(value);
            if (value == null) {
                // optional field.
                return FormValidation.ok();
            }
            
            if (!isValidVariableName(value)) {
                return FormValidation.error(Messages.CopyArtifact_InvalidVariableName());
            }
            
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> clazz) {
            return true;
        }

        public String getDisplayName() {
            return Messages.CopyArtifact_DisplayName();
        }

    }

    // Listen for project renames and update property here if needed.
    @Extension
    public static final class ListenerImpl extends ItemListener {
        @Override
        public void onRenamed(Item item, String oldName, String newName) {
            String oldFullName = Items.getCanonicalName(item.getParent(), oldName);
            String newFullName = Items.getCanonicalName(item.getParent(), newName);
            Jenkins jenkins = Jenkins.getInstance();
            if (jenkins == null) {
                LOGGER.log(Level.SEVERE, "Jenkins instance is no longer available.");
                return;
            }
            for (AbstractProject<?,?> project
                    : jenkins.getAllItems(AbstractProject.class)) {
                try {
                for (CopyArtifact ca : getCopiers(project)) {
                    String projectName = ca.getProjectName();
                    if (projectName == null) {
                        // JENKINS-27475 (not sure why this happens).
                        continue;
                    }

                    String suffix = "";
                    // Support rename for "MatrixJobName/AxisName=value" type of name
                    int i = projectName.indexOf('=');
                    if (i > 0) {
                        int end = projectName.substring(0,i).lastIndexOf('/');
                        suffix = projectName.substring(end);
                        projectName = projectName.substring(0, end);
                    }

                    ItemGroup context = project.getParent();
                    String newProjectName = Items.computeRelativeNamesAfterRenaming(oldFullName, newFullName, projectName, context);
                    if (!projectName.equals(newProjectName)) {
                        ca.project = newProjectName + suffix;
                        project.save();
                    }
                }
                } catch (IOException ex) {
                    Logger.getLogger(ListenerImpl.class.getName()).log(Level.WARNING,
                            "Failed to resave project " + project.getName()
                            + " for project rename in CopyArtifact build step ("
                            + oldName + " =>" + newName + ")", ex);
                }
            }
        }

        private static List<CopyArtifact> getCopiers(AbstractProject<?,?> project) throws IOException {
            List<CopyArtifact> copiers = getCopyArtifactsInProject(project);
            for (CopyArtifact copier : copiers) {
                copier.upgradeIfNecessary(project);
            }
            return copiers;
        }
    }

    private static class EnvAction implements EnvironmentContributingAction {
        // Decided not to record this data in build.xml, so marked transient:
        private transient Map<String,String> data = new HashMap<String,String>();

        @Nullable
        private String calculateDefaultSuffix(@Nonnull Run<?,?> build, @Nonnull Run<?,?> src, @Nonnull String projectName) {
            ItemGroup<?> ctx = getItemGroup(build);
            Job<?,?> item = src.getParent();
            // Use full name if configured with absolute path
            // and relative otherwise
            projectName = projectName.startsWith("/") ? item.getFullName() : item.getRelativeNameFrom(ctx);
            if (projectName == null) {
                // this is a case when the copying project doesn't belong to Jenkins item tree.
                // (e.g. promotion for Promoted Builds plugin)
                LOGGER.log(
                        Level.WARNING,
                        "Failed to calculate a relative path of {0} from {2}",
                        new Object[] {
                                item.getFullName(),
                                ctx.getFullName(),
                        }
                );
                return null;
            }
            
            return  projectName.toUpperCase().replaceAll("[^A-Z]+", "_"); // Only use letters and _
        }
        
        private void add(
                @Nonnull Run<?,?> build,
                @Nonnull Run<?,?> src,
                @Nonnull String projectName,
                @Nullable String resultVariableSuffix
        ) {
            if (data==null) return;
            
            if (!isValidVariableName(resultVariableSuffix)) {
                resultVariableSuffix = calculateDefaultSuffix(build, src, projectName);
                if (resultVariableSuffix == null) {
                    return;
                }
            }
            data.put(
                String.format("COPYARTIFACT_BUILD_NUMBER_%s", resultVariableSuffix),
                Integer.toString(src.getNumber())
            );
        }

        public void buildEnvVars(AbstractBuild<?,?> build, EnvVars env) {
            if (data!=null) env.putAll(data);
        }

        public String getIconFileName() { return null; }
        public String getDisplayName() { return null; }
        public String getUrlName() { return null; }
    }
}
