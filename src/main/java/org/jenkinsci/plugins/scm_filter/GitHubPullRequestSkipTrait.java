package org.jenkinsci.plugins.scm_filter;

import hudson.Extension;
import hudson.plugins.git.GitSCM;
import hudson.scm.SCMDescriptor;

import jenkins.scm.api.SCMHead;
import jenkins.scm.api.trait.*;

import org.jenkinsci.plugins.github_branch_source.GitHubSCMBuilder;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSourceRequest;
import org.jenkinsci.plugins.github_branch_source.PullRequestSCMHead;

import org.kohsuke.github.*;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.List;


public class GitHubPullRequestSkipTrait extends SCMSourceTrait {


    @DataBoundConstructor
    public GitHubPullRequestSkipTrait () {
    }


    /**
     * {@inheritDoc}
     */
    @Override
    protected void decorateContext (SCMSourceContext<?, ?> context) {
        context.withFilter(new GitHubPullRequestSkipTrait.ExcludePRsSCMHeadFilter());
    }


    /**
     * Our descriptor.
     */
    @Extension
    @SuppressWarnings("unused") // instantiated by Jenkins
    public static class PullRequestSkipTraitDescriptorImpl extends SCMSourceTraitDescriptor {


        /**
         * {@inheritDoc}
         */
        @Override
        @Nonnull
        public String getDisplayName () {
            return "Pull request title filtering behaviour";
        }


        /**
         * {@inheritDoc}
         */
        @Override
        public boolean isApplicableToSCM (@Nonnull SCMDescriptor<?> scm) {
            return scm instanceof GitSCM.DescriptorImpl;
        }


        /**
         * {@inheritDoc}
         */
        @Override
        public boolean isApplicableToBuilder (@Nonnull Class<? extends SCMBuilder> builderClass) {
            return GitHubSCMBuilder.class.isAssignableFrom(builderClass);
        }
    }


    /**
     * Filter that excludes pull requests according to its PR title
     * if it contains [ci skip], [skip ci], or [wip], case insensitive.
     * The words "ci" and "skip" can be separated by either a dash, an underscore, or a single space.
     */
    public static class ExcludePRsSCMHeadFilter extends SCMHeadFilter {


        ExcludePRsSCMHeadFilter () {
        }


        @Override
        public boolean isExcluded (
                @Nonnull SCMSourceRequest scmSourceRequest,
                @Nonnull SCMHead scmHead
        ) throws IOException, InterruptedException {

            if (scmHead instanceof PullRequestSCMHead) {
                GHRepository repository = ((GitHubSCMSourceRequest) scmSourceRequest).getRepository();

                // name for pull requests are formatted as "PR-<number>"
                int prNumber = Integer.parseInt(scmHead.getName().substring(3));

                GHPullRequest pullRequest = repository.getPullRequest(prNumber);

                String prTitle = pullRequest.getTitle();

                boolean checkPrTitle = prTitle.matches("(?i).*\\[(wip|ci[\\- _]skip|skip[\\- _]ci)\\].*");

                // TODO: enable this one once github-api 1.99 version or later is released
                boolean draftPr = false; //pullRequest.isDraft();
                boolean anyApproved = false;


                // we are NOT triggering Jenkins if there were no reviews yet
                PagedIterable<GHPullRequestReview> reviews = pullRequest.listReviews();
                if (reviews == null || reviews.asList() == null || reviews.asList().size() == 0) {
                    System.out.println("PR [" + prNumber + "] has no reviews. Skipping");
                    return true;
                }


                // we want to have at least 1 approved review before running Jenkins
                List<GHPullRequestReview> reviewsList = reviews.asList();
                for (GHPullRequestReview review: reviewsList) {
                    GHPullRequestReviewState reviewState = review.getState();
                    System.out.println("PR: [" +prNumber + "]; Review by [" + review.getUser().getEmail() + "]; Status: [" + reviewState.toString() + "]");

                    if (reviewState == GHPullRequestReviewState.APPROVED || review.getBody().toLowerCase().contains("LGTM"))
                        anyApproved = true;
                }


                // we skip triggering Jenkins if there's WIP word in PR title, or that's draft PR.
                return (checkPrTitle || draftPr || !anyApproved);
            }

            return false;
        }
    }
}
