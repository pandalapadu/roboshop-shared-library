def updateCommitStatus(
    String state,
    String description,
    String context = 'Jenkins CI'
) {

    withCredentials([
        string(
            credentialsId: 'github-token',
            variable: 'GITHUB_TOKEN'
        )
    ]) {

        def repoUrl = sh(
            script: 'git remote get-url origin',
            returnStdout: true
        ).trim()

        def repoPath = repoUrl
            .replaceAll(/.*github\.com[\/:]/, '')
            .replaceAll(/\.git$/, '')

        withEnv([
            "COMMIT_STATE=${state.toLowerCase()}",
            "COMMIT_DESC=${description}",
            "COMMIT_CONTEXT=${context}",
            "REPO_PATH=${repoPath}",
            "COMMIT_SHA=${env.GIT_COMMIT}",
            "BUILD_LINK=${env.BUILD_URL}"
        ]) {

            sh '''
                HTTP_STATUS=$(jq -n \
                    --arg state   "$COMMIT_STATE" \
                    --arg url     "$BUILD_LINK" \
                    --arg desc    "$COMMIT_DESC" \
                    --arg context "$COMMIT_CONTEXT" \
                    '{
                        state: $state,
                        target_url: $url,
                        description: $desc,
                        context: $context
                    }' |
                curl -s \
                    -o commit-status-response.json \
                    -w "%{http_code}" \
                    -X POST \
                    -H "Authorization: Bearer $GITHUB_TOKEN" \
                    -H "Accept: application/vnd.github+json" \
                    -H "Content-Type: application/json" \
                    -H "X-GitHub-Api-Version: 2022-11-28" \
                    --data @- \
                    "https://api.github.com/repos/$REPO_PATH/statuses/$COMMIT_SHA"
                )

                if [ "$HTTP_STATUS" -lt 200 ] || [ "$HTTP_STATUS" -ge 300 ]; then

                    echo "GitHub commit status update failed"
                    echo "HTTP Status : $HTTP_STATUS"
                    echo "Context     : $COMMIT_CONTEXT"

                    cat commit-status-response.json

                    rm -f commit-status-response.json

                    exit 1
                fi

                echo "GitHub commit status updated successfully"
                echo "Context : $COMMIT_CONTEXT"
                echo "State   : $COMMIT_STATE"

                rm -f commit-status-response.json
            '''
        }
    }
}


/*
 * ==========================================================
 * SAFE GITHUB COMMIT STATUS
 * ==========================================================
 *
 * GitHub status reporting should not hide the actual
 * pipeline/test failure.
 */

def safeUpdateCommitStatus(
    String state,
    String description,
    String context = 'Jenkins CI'
) {

    try {

        updateCommitStatus(
            state,
            description,
            context
        )

        echo "GitHub status updated: ${context} -> ${state}"

    }
    catch (Exception e) {

        echo "WARNING: GitHub status update failed"
        echo "Context : ${context}"
        echo "State   : ${state}"
        echo "Error   : ${e.message}"

        /*
         * Do not fail Jenkins because GitHub status
         * reporting failed.
         */
    }
}


/*
 * ==========================================================
 * VALIDATE COMMIT STATUS
 * ==========================================================
 */

def validateCommitStatus(
    String commitSha,
    List requiredContexts
) {

    withCredentials([
        string(
            credentialsId: 'github-token',
            variable: 'GITHUB_TOKEN'
        )
    ]) {

        def repoUrl = sh(
            script: 'git remote get-url origin',
            returnStdout: true
        ).trim()

        def repoPath = repoUrl
            .replaceAll(/.*github\.com[\/:]/, '')
            .replaceAll(/\.git$/, '')

        withEnv([
            "REPO_PATH=${repoPath}",
            "COMMIT_SHA=${commitSha}",
            "REQUIRED_CONTEXTS=${requiredContexts.join(',')}"
        ]) {

            sh '''
                set -e

                curl -sf \
                    -H "Authorization: Bearer $GITHUB_TOKEN" \
                    -H "Accept: application/vnd.github+json" \
                    -H "X-GitHub-Api-Version: 2022-11-28" \
                    "https://api.github.com/repos/$REPO_PATH/commits/$COMMIT_SHA/status" \
                    -o commit-status.json

                FAILED=0

                for CTX in $(echo "$REQUIRED_CONTEXTS" | tr ',' ' '); do

                    STATE=$(jq -r \
                        --arg ctx "$CTX" \
                        '.statuses[] |
                        select(.context == $ctx) |
                        .state' \
                        commit-status.json |
                        head -n1)

                    echo "context=$CTX state=${STATE:-missing}"

                    if [ "$STATE" != "success" ]; then
                        FAILED=1
                    fi

                done

                rm -f commit-status.json

                if [ "$FAILED" -eq 1 ]; then
                    echo "One or more required status checks are not successful"
                    exit 1
                fi
            '''
        }
    }
}


/*
 * ==========================================================
 * GET POD IP
 * ==========================================================
 */

def getPodIP(
    String namespace,
    String component
) {

    def ip = sh(
        script: """
            kubectl get pod \
                -n ${namespace} \
                -l component=${component} \
                -o jsonpath='{.items[0].status.podIP}'
        """,
        returnStdout: true
    ).trim()

    if (!ip) {

        error(
            "Could not resolve pod IP for component '${component}' " +
            "in namespace '${namespace}'"
        )
    }

    return ip
}


/*
 * ==========================================================
 * TAG COMMIT
 * ==========================================================
 */

def tagCommit(
    String commitSha,
    String tag
) {

    withCredentials([
        string(
            credentialsId: 'github-token',
            variable: 'GITHUB_TOKEN'
        )
    ]) {

        def repoUrl = sh(
            script: 'git remote get-url origin',
            returnStdout: true
        ).trim()

        def repoPath = repoUrl
            .replaceAll(/.*github\.com[\/:]/, '')
            .replaceAll(/\.git$/, '')

        withEnv([
            "REPO_PATH=${repoPath}",
            "COMMIT_SHA=${commitSha}",
            "TAG_NAME=${tag}"
        ]) {

            sh '''
                HTTP_STATUS=$(jq -n \
                    --arg ref "refs/tags/$TAG_NAME" \
                    --arg sha "$COMMIT_SHA" \
                    '{
                        ref: $ref,
                        sha: $sha
                    }' |
                curl -s \
                    -o tag-response.json \
                    -w "%{http_code}" \
                    -X POST \
                    -H "Authorization: Bearer $GITHUB_TOKEN" \
                    -H "Accept: application/vnd.github+json" \
                    -H "X-GitHub-Api-Version: 2022-11-28" \
                    --data @- \
                    "https://api.github.com/repos/$REPO_PATH/git/refs"
                )

                if [ "$HTTP_STATUS" -lt 200 ] || [ "$HTTP_STATUS" -ge 300 ]; then

                    echo "Failed to tag commit"
                    echo "HTTP Status: $HTTP_STATUS"

                    cat tag-response.json

                    rm -f tag-response.json

                    exit 1
                fi

                rm -f tag-response.json
            '''
        }
    }
}


/*
 * ==========================================================
 * JIRA CREATE
 * ==========================================================
 */

def createJiraTicket(
    String projectKey,
    String commitId,
    String version
) {

    def fields = jiraGetFields()

    if (!fields.successful) {
        error("Could not fetch Jira fields: ${fields.error}")
    }

    def commitFieldId =
        fields.data.find {
            it.name?.trim()?.equalsIgnoreCase('Commit ID')
        }?.id

    def versionFieldId =
        fields.data.find {
            it.name?.trim()?.equalsIgnoreCase('Version')
        }?.id

    if (!commitFieldId || !versionFieldId) {

        echo(
            "Fields returned by jiraGetFields: " +
            fields.data.collect {
                "${it.name} (${it.id})"
            }.join(', ')
        )

        error(
            "Could not find the 'Commit ID' / 'Version' custom fields"
        )
    }

    def issueFields = [
        project  : [key: projectKey],
        issuetype: [name: 'Task'],
        summary  : "Release ${version} - ${commitId}"
    ]

    issueFields[commitFieldId] = commitId
    issueFields[versionFieldId] = version

    def result =
        jiraNewIssue(
            issue: [
                fields: issueFields
            ]
        )

    if (!result.successful) {
        error("Failed to create Jira ticket: ${result.error}")
    }

    return result.data.key
}


/*
 * ==========================================================
 * JIRA TRANSITION
 * ==========================================================
 */

def transitionJiraIssue(
    String issueKey,
    String targetStatus
) {

    def issue =
        jiraGetIssue(
            idOrKey: issueKey
        )

    if (!issue.successful) {

        error(
            "Could not fetch ${issueKey}: ${issue.error}"
        )
    }

    if (issue.data.fields.status.name == targetStatus) {

        echo(
            "${issueKey} is already at '${targetStatus}'"
        )

        return
    }

    def transitions =
        jiraGetIssueTransitions(
            idOrKey: issueKey
        )

    if (!transitions.successful) {

        error(
            "Could not fetch transitions for ${issueKey}: " +
            "${transitions.error}"
        )
    }

    def transitionId =
        transitions.data.transitions.find {
            it.to.name == targetStatus
        }?.id

    if (!transitionId) {

        error(
            "No available transition to status " +
            "'${targetStatus}' for ${issueKey}"
        )
    }

    def result =
        jiraTransitionIssue(
            idOrKey: issueKey,
            input: [
                transition: [
                    id: transitionId
                ]
            ]
        )

    if (!result.successful) {

        error(
            "Failed to transition ${issueKey} " +
            "to '${targetStatus}': ${result.error}"
        )
    }
}


/*
 * ==========================================================
 * SAFE JIRA TRANSITION
 * ==========================================================
 */

def safeTransitionJiraIssue(
    String issueKey,
    String targetStatus
) {

    try {

        transitionJiraIssue(
            issueKey,
            targetStatus
        )

    }
    catch (Exception e) {

        echo(
            "Warning: could not transition Jira ticket " +
            "${issueKey} to '${targetStatus}': ${e.message}"
        )
    }
}


/*
 * ==========================================================
 * CREATE PULL REQUEST
 * ==========================================================
 */

def createPullRequest(
    String base = 'main',
    String title = '',
    String body = ''
) {

    withCredentials([
        string(
            credentialsId: 'github-token',
            variable: 'GITHUB_TOKEN'
        )
    ]) {

        def repoUrl = sh(
            script: 'git remote get-url origin',
            returnStdout: true
        ).trim()

        def repoPath = repoUrl
            .replaceAll(/.*github\.com[\/:]/, '')
            .replaceAll(/\.git$/, '')

        def head = env.BRANCH_NAME

        withEnv([
            "REPO_PATH=${repoPath}",
            "PR_BASE=${base}",
            "PR_HEAD=${head}",
            "PR_TITLE=${title ?: "${head} -> ${base}"}",
            "PR_BODY=${body ?: "Automated PR from Jenkins build ${env.BUILD_URL}"}"
        ]) {

            sh '''
                set -e

                ORG="${REPO_PATH%%/*}"

                EXISTING=$(curl -sf \
                    -H "Authorization: Bearer $GITHUB_TOKEN" \
                    -H "Accept: application/vnd.github+json" \
                    "https://api.github.com/repos/$REPO_PATH/pulls?head=${ORG}:${PR_HEAD}&base=${PR_BASE}&state=open")

                COUNT=$(echo "$EXISTING" | jq 'length')

                if [ "$COUNT" -gt 0 ]; then

                    URL=$(echo "$EXISTING" | jq -r '.[0].html_url')

                    echo "PR already open: $URL"

                else

                    RESPONSE=$(jq -n \
                        --arg title "$PR_TITLE" \
                        --arg head "$PR_HEAD" \
                        --arg base "$PR_BASE" \
                        --arg body "$PR_BODY" \
                        '{
                            title: $title,
                            head: $head,
                            base: $base,
                            body: $body
                        }' |
                    curl -f \
                        -X POST \
                        -H "Authorization: Bearer $GITHUB_TOKEN" \
                        -H "Accept: application/vnd.github+json" \
                        -H "Content-Type: application/json" \
                        --data @- \
                        "https://api.github.com/repos/$REPO_PATH/pulls")

                    URL=$(echo "$RESPONSE" | jq -r '.html_url')

                    echo "Opened PR: $URL"
                fi
            '''
        }
    }
}