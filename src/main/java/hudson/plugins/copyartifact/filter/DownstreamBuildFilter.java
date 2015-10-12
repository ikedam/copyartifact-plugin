/*
 * The MIT License
 * 
 * Copyright (c) 2014 IKEDA Yasuyuki
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

package hudson.plugins.copyartifact.filter;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import jenkins.model.Jenkins;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import hudson.Extension;
import hudson.Util;
import hudson.model.AutoCompletionCandidates;
import hudson.model.Item;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Job;
import hudson.model.Run;
import hudson.plugins.copyartifact.BuildFilter;
import hudson.plugins.copyartifact.BuildFilterDescriptor;
import hudson.plugins.copyartifact.CopyArtifactPickContext;
import hudson.util.FormValidation;

/**
 * Select a build which is a downstream of a specified build.
 * @since 2.0
 */
public class DownstreamBuildFilter extends BuildFilter {
    @Nonnull
    private final String upstreamProjectName;
    @Nonnull
    private final String upstreamBuildNumber;
    
    /**
     * @param upstreamProjectName
     * @param upstreamBuildNumber
     */
    @DataBoundConstructor
    public DownstreamBuildFilter(@CheckForNull String upstreamProjectName, @CheckForNull String upstreamBuildNumber) {
        this.upstreamProjectName = Util.fixNull(upstreamProjectName).trim();
        this.upstreamBuildNumber = Util.fixNull(upstreamBuildNumber).trim();
    }
    
    /**
     * @return upstream project name. May include variable expression.
     */
    @Nonnull
    public String getUpstreamProjectName() {
        return upstreamProjectName;
    }
    
    /**
     * @return upstream build number. May include variable expression.
     */
    @Nonnull
    public String getUpstreamBuildNumber() {
        return upstreamBuildNumber;
    }
    
    @Override
    public boolean isSelectable(@Nonnull Run<?, ?> run, @Nonnull CopyArtifactPickContext context) {
        if (!(run instanceof AbstractBuild<?,?>)) {
            context.logInfo("{0}: Only applicable to AbstractBuild: but is {1}.", getDisplayName(), run.getClass().getName());
            return false;
        }
        
        Job<?,?> copier = context.getCopierBuild().getParent();
        if (copier instanceof AbstractProject<?,?>) {
            copier = ((AbstractProject<?,?>)copier).getRootProject();
        }
        
        String projectName = context.getEnvVars().expand(getUpstreamProjectName());
        String buildNumber = context.getEnvVars().expand(getUpstreamBuildNumber());
        
        if (StringUtils.isBlank(projectName)) {
            context.logInfo("{0}: Upstream project name gets empty.", getDisplayName());
            return false;
        }
        
        if (StringUtils.isBlank(buildNumber)) {
            context.logInfo("{0}: Upstream build number gets empty.", getDisplayName());
            return false;
        }
        
        AbstractProject<?,?> upstreamProject = context.getJenkins().getItem(
                projectName,
                copier,
                AbstractProject.class
        );
        if (upstreamProject == null || !upstreamProject.hasPermission(Item.READ)) {
            context.logInfo(String.format("{0}: Upstream project '{1}' is not found.", getDisplayName(), projectName));
            return false;
        }
        AbstractBuild<?,?> upstreamBuild = ((AbstractBuild<?,?>)run).getUpstreamRelationshipBuild(upstreamProject);
        if (upstreamBuild == null || !upstreamBuild.hasPermission(Item.READ)) {
            context.logDebug(
                    "{0}: No upstream build of project '{1}' is found for build {2}.",
                    getDisplayName(),
                    upstreamProject.getFullName(),
                    run.getFullDisplayName()
            );
            return false;
        }
        
        try {
            int number = Integer.parseInt(buildNumber);
            if (number == upstreamBuild.getNumber()) {
                // build number matches.
                return true;
            }
        } catch (NumberFormatException e) {
            // Ignore. Nothing to do.
        }
        
        if (buildNumber.equals(upstreamBuild.getId()) || buildNumber.equals(upstreamBuild.getDisplayName())) {
            // id or display name matches.
            return true;
        }
        
        context.logDebug(
                "{0}: build {1} doesn't match {2}-{3}.",
                getDisplayName(),
                run.getParent().getFullName(),
                run.getDisplayName(),
                buildNumber
        );
        return false;
    }
    
    @Extension
    public static final class DescriptorImpl extends BuildFilterDescriptor {
        @Override
        public String getDisplayName() {
            return Messages.DownstreamBuildFilter_DisplayName();
        }
        
        /**
         * @param str
         * @return whether a value contains variable expressions.
         */
        protected boolean containsVariable(String str) {
            return !StringUtils.isBlank(str) && str.indexOf('$') >= 0;
        }
        
        /**
         * Validates a form input to "Upstream Project Name"
         * 
         * @return
         */
        public FormValidation doCheckUpstreamProjectName(
                @AncestorInPath AbstractProject<?,?> project,
                @QueryParameter String upstreamProjectName
        ) {
            upstreamProjectName = StringUtils.trim(upstreamProjectName);
            if (StringUtils.isBlank(upstreamProjectName)) {
                return FormValidation.error(Messages.DownstreamBuildFilter_UpstreamProjectName_Required());
            }
            
            if (containsVariable(upstreamProjectName)) {
                return FormValidation.ok();
            }
            
            Jenkins jenkins = Jenkins.getInstance();
            if (jenkins == null) {
                // Jenkins is unavailable and validation is useless.
                return FormValidation.ok();
            }
            
            AbstractProject<?,?> upstreamProject = jenkins.getItem(
                    upstreamProjectName, project.getRootProject(), AbstractProject.class
            );
            if (upstreamProject == null || !upstreamProject.hasPermission(Item.READ)) {
                return FormValidation.error(Messages.DownstreamBuildFilter_UpstreamProjectName_NotFound());
            }
            return FormValidation.ok();
        }
        
        /**
         * Validates a form input to "Upstream Build Number"
         * 
         * @return
         */
        public FormValidation doCheckUpstreamBuildNumber(
                @AncestorInPath AbstractProject<?,?> project,
                @QueryParameter String upstreamProjectName,
                @QueryParameter String upstreamBuildNumber
        ) {
            // This is useless in almost all cases as this is usually specified with variables.
            
            upstreamProjectName = StringUtils.trim(upstreamProjectName);
            upstreamBuildNumber = StringUtils.trim(upstreamBuildNumber);
            
            if (StringUtils.isBlank(upstreamProjectName) || containsVariable(upstreamProjectName)) {
                // skip validation
                return FormValidation.ok();
            }
            
            if (StringUtils.isBlank(upstreamBuildNumber)) {
                return FormValidation.error(Messages.DownstreamBuildFilter_UpstreamBuildNumber_Required());
            }
            
            if (containsVariable(upstreamBuildNumber)) {
                return FormValidation.ok();
            }
            
            Jenkins jenkins = Jenkins.getInstance();
            if (jenkins == null) {
                // Jenkins is unavailable and validation is useless.
                return FormValidation.ok();
            }
            
            AbstractProject<?,?> upstreamProject = jenkins.getItem(
                    upstreamProjectName, project.getRootProject(), AbstractProject.class
            );
            if (upstreamProject == null || !upstreamProject.hasPermission(Item.READ)) {
                return FormValidation.ok();
            }
            
            try {
                int number = Integer.parseInt(upstreamBuildNumber);
                AbstractBuild<?,?> upstreamBuild = upstreamProject.getBuildByNumber(number);
                if (upstreamBuild != null && upstreamBuild.hasPermission(Item.READ)) {
                    // build number matches.
                    return FormValidation.ok();
                }
            } catch (NumberFormatException e) {
                // Ignore. Nothing to do.
            }
            
            {
                AbstractBuild<?,?> upstreamBuild = upstreamProject.getBuild(upstreamBuildNumber);
                if (upstreamBuild != null && upstreamBuild.hasPermission(Item.READ)) {
                    // build id matches.
                    return FormValidation.ok();
                }
            }
            
            {
                for(
                        AbstractBuild<?,?> upstreamBuild = upstreamProject.getLastCompletedBuild();
                        upstreamBuild != null;
                        upstreamBuild = upstreamBuild.getPreviousCompletedBuild()
                ) {
                    if (upstreamBuild.getDisplayName().equals(upstreamBuildNumber)) {
                        // display name matches.
                        return FormValidation.ok();
                    }
                }
            }
            
            return FormValidation.error(Messages.DownstreamBuildFilter_UpstreamBuildNumber_NotFound());
        }
        
        /**
         * Fill the project name automatically.
         * 
         * @param value
         * @param project
         * @return
         */
        public AutoCompletionCandidates doAutoCompleteUpstreamProjectName(
                @QueryParameter String value,
                @AncestorInPath AbstractProject<?,?> project
        ) {
            // Specified Item to allow to autocomplete folders (maybe confusing...).
            return AutoCompletionCandidates.ofJobNames(Item.class, value, project, project.getParent());
        }
    }
}
