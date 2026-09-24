#!/bin/bash

# Slack notification script for the dartboard e2e (deploy -> load -> Qase k6 suite) pipeline.
# Sends one summary message per build, reporting the aggregate Qase test run results.

set -e

# Function to send Slack notification
send_slack_notification() {
	local status="$1"
	local message="$2"
	local bot_token="$3"
	local channel="$4"

	if [ -z "$bot_token" ] || [ -z "$channel" ]; then
		echo "DARTBOARD_SLACK_BOT_TOKEN and DARTBOARD_SLACK_CHANNEL must be set"
		return 1
	fi

	local payload
	payload=$(cat <<EOF
{
  "channel": "$channel",
  "text": "$message",
  "username": "Dartboard Test Reporter"
}
EOF
)

	# Bounded: this runs in the post block, so a stalled Slack call would hold
	# the build open against the outer timeout rather than the few seconds a
	# notification is worth.
	local response
	if ! response=$(curl --fail --silent --show-error -X POST \
		--connect-timeout 10 --max-time 30 --retry 2 --retry-delay 3 \
		-H "Content-type: application/json; charset=utf-8" \
		-H "Authorization: Bearer $bot_token" \
		--data "$payload" \
		"https://slack.com/api/chat.postMessage"); then
		echo "Failed to send Slack notification (HTTP/network error)"
		return 1
	fi

	# Slack returns HTTP 200 even for errors (e.g. invalid_auth, channel_not_found)
	if [[ "$response" != *'"ok":true'* ]]; then
		echo "Failed to send Slack notification (API error): $response"
		return 1
	fi

	return 0
}

# Emit a "• *Label:* value" message line, or nothing when the value is unavailable.
# Fields are skipped gracefully so the message stays correct as fields become optional.
append_field() {
	local label="$1"
	local value="$2"

	if [ -n "$value" ] && [ "$value" != "Unknown" ]; then
		printf '• *%s:* %s\\n' "$label" "$value"
	fi
}

# Main execution
send_jenkins_e2e_notification() {
	local build_status="$1"
	local job_name="${JOB_NAME:-Unknown Job}"
	local build_number="${BUILD_NUMBER:-Unknown}"
	local build_url="${BUILD_URL:-}"

	# Get Slack bot token and channel from Secrets Manager
	local slack_bot_token="${DARTBOARD_SLACK_BOT_TOKEN:-}"
	local slack_channel="${DARTBOARD_SLACK_CHANNEL:-}"

	local emoji="✅"
	local status_text="PASSED"

	if [ "$build_status" = "FAILURE" ]; then
		emoji="❌"
		status_text="FAILED"
	elif [ "$build_status" = "UNSTABLE" ]; then
		emoji="⚠️"
		status_text="UNSTABLE"
	fi

	local message="*k6 Tests $status_text* $emoji\n"
	message+="• *Job:* $job_name\n"

	# Add build number with link
	if [ -n "$build_url" ] && [ "$build_number" != "Unknown" ]; then
		message+="• *Build:* <$build_url|#$build_number>\n"
	elif [ "$build_number" != "Unknown" ]; then
		message+="• *Build:* #$build_number\n"
	fi

	# Qase test run summary, published by the qase-k6-cli 'runstats' subcommand.
	local qase_summary=""

	if [[ "${QASE_RUN_TOTAL:-}" =~ ^[0-9]+$ &&
		"${QASE_RUN_PASSED:-}" =~ ^[0-9]+$ &&
		"${QASE_RUN_FAILED:-}" =~ ^[0-9]+$ &&
		"${QASE_RUN_EXCEEDED_THRESHOLDS:-}" =~ ^[0-9]+$ &&
		"${QASE_RUN_URL:-}" =~ ^https://app\.qase\.io/run/[^[:space:]/]+/dashboard/[0-9]+$ ]]; then
		qase_summary="${QASE_RUN_PASSED}/${QASE_RUN_TOTAL} passed - ${QASE_RUN_FAILED} failed, ${QASE_RUN_EXCEEDED_THRESHOLDS} thresholds exceeded"
		if [ -n "${QASE_RUN_URL}" ]; then
			qase_summary="<${QASE_RUN_URL}|${qase_summary}>"
		fi
	else
		echo "Skipping Qase run summary: required QASE_RUN_* values are unavailable or invalid."
	fi

	message+=$(append_field "Qase Run" "$qase_summary")
	message+=$(append_field "Rancher Version" "${RANCHER_VERSION:-}")
	message+=$(append_field "K8s Version" "${KUBERNETES_VERSION:-}")

	message+="• *Timestamp:* $(date -u '+%Y-%m-%d %H:%M:%S UTC')"

	echo "Sending Slack notification for $build_status build..."
	if send_slack_notification "$build_status" "$message" "$slack_bot_token" "$slack_channel"; then
		echo "Slack notification sent successfully"
		return 0
	else
		echo "Failed to send Slack notification"
		return 1
	fi
}

# Execute main function with build status
send_jenkins_e2e_notification "$1"
