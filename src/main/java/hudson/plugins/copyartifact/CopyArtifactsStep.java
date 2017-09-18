/*
 * The MIT License
 *
 * Copyright (c) 2017 IKEDA Yasuyuki
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

import javax.annotation.Nonnull;

import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractSynchronousNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import com.google.inject.Inject;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.Run;
import hudson.model.TaskListener;

/**
 * Simple step to have {@link CopyArtifact} available for pipeline
 */
public class CopyArtifactsStep extends AbstractStepImpl {

    private String project;
    private String parameters;
    private String includes;
    private String excludes;
    private String target;
    private BuildSelector selector;
    private boolean flatten;
    private boolean optional;
    private boolean doNotFingerprintArtifacts;
    private String resultVariableSuffix;

    /**
     * ctor
     *
     * @param project project name to copy artifacts from
     */
    @DataBoundConstructor
    public CopyArtifactsStep(String project) {
        this.project = project;
    }

    /**
     * @param parameters parameters to filter builds
     */
    @DataBoundSetter
    public void setParameters(String parameters) {
        this.parameters = Util.fixEmptyAndTrim(parameters);
    }

    /**
     * @param includes filter for artifacts to copy
     */
    @DataBoundSetter
    public void setIncludes(String includes) {
        this.includes = Util.fixNull(includes).trim();
    }

    /**
     * @param excludes filter for artifacts not to copy
     */
    @DataBoundSetter
    public void setExcludes(String excludes) {
        this.excludes = Util.fixNull(excludes).trim();
    }

    /**
     * @param target target directory
     */
    @DataBoundSetter
    public void setTarget(String target) {
        this.target = Util.fixNull(target).trim();
    }

    /**
     * @param selector selector for build to copy from
     */
    @DataBoundSetter
    public void setSelector(@Nonnull BuildSelector selector) {
        this.selector = selector;
    }

    /**
     * @param flatten <code>true</code> to ignore directory structures of artifacts
     */
    @DataBoundSetter
    public void setFlatten(boolean flatten) {
        this.flatten = flatten;
    }

    /**
     * @param optional <code>true</code> not to fail the build if no build is found to copy from
     */
    @DataBoundSetter
    public void setOptional(boolean optional) {
        this.optional = optional;
    }

    /**
     * @param fingerprintArtifacts
     *     <code>false</code> not to fingerprint artifacts.
     *     The default is <code>true</code>
     */
    @DataBoundSetter
    public void setFingerprintArtifacts(boolean fingerprintArtifacts) {
        this.doNotFingerprintArtifacts = !fingerprintArtifacts;
    }

    /**
     * @param resultVariableSuffix Variable suffix to use.
     */
    @DataBoundSetter
    public void setResultVariableSuffix(String resultVariableSuffix) {
        this.resultVariableSuffix = Util.fixEmptyAndTrim(resultVariableSuffix);
    }

    private CopyArtifact createBuildStep() {
        CopyArtifact step = new CopyArtifact(project);
        step.setParameters(parameters);
        step.setFilter(includes);
        step.setExcludes(excludes);
        step.setTarget(target);
        step.setSelector(selector);
        step.setFlatten(flatten);
        step.setOptional(optional);
        step.setFingerprintArtifacts(!doNotFingerprintArtifacts);
        step.setResultVariableSuffix(resultVariableSuffix);
        return step;
    }

    private static final class Execution extends AbstractSynchronousNonBlockingStepExecution<Void> {

        @Inject private transient CopyArtifactsStep step;
        @StepContextParameter private transient Run<?,?> run;
        @StepContextParameter private transient FilePath workspace;
        @StepContextParameter private transient Launcher launcher;
        @StepContextParameter private transient TaskListener listener;

        /**
         * {@inheritDoc}
         */
        @Override
        protected Void run() throws Exception {
            step.createBuildStep().perform(run, workspace, launcher, listener);
            return null;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String getStatus() {
            return "CopyArtifacts: " + super.getStatus();
        }

        private static final long serialVersionUID = 1L;

    }

    @Extension
    public static final class DescriptorImpl extends AbstractStepDescriptorImpl {

        /**
         * {@inheritDoc}
         */
        public DescriptorImpl() {
            super(Execution.class);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String getFunctionName() {
            return "copyArtifacts";
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String getDisplayName() {
            return Messages.CopyArtifact_DisplayName();
        }
    }
}
