package main

import (
	"context"
	"fmt"
	"os"
	"strconv"

	qase_config "github.com/qase-tms/qase-go/pkg/qase-go/config"
	"github.com/rancher/dartboard/internal/qase"
	"github.com/sirupsen/logrus"
)

// getRunStats fetches aggregate pass/fail stats for a Qase test run and prints
// them as KEY=VALUE lines so a CI pipeline can source them into env vars.
func getRunStats(runIDOverride string) {
	logrus.Info("Running qase-k6-cli runstats")

	if projectID == "" {
		logrus.Fatalf("Missing required environment variable: %s", qase_config.QaseTestOpsProjectEnvVar)
	}

	runIDStr := runIDOverride
	if runIDStr == "" {
		runIDStr = os.Getenv(qase_config.QaseTestOpsRunIDEnvVar)
	}

	if runIDStr == "" {
		logrus.Fatal("runID is required for runstats subcommand")
	}

	runIDVal, err := strconv.ParseInt(runIDStr, 10, 64)
	if err != nil {
		logrus.Fatalf("Invalid runID: %v", err)
	}

	qaseClient = qase.SetupQaseClient()

	include := "stats"

	run, err := qaseClient.GetTestRun(context.Background(), projectID, runIDVal, &include)
	if err != nil {
		logrus.Fatalf("Failed to get test run: %v", err)
	}

	stats := run.GetStats()
	total := int64(stats.GetTotal())
	passed := int64(stats.GetPassed())

	statusCounts, err := qaseClient.GetRunResultStatusCounts(context.Background(), projectID, runIDVal)
	if err != nil {
		logrus.Warnf("Unable to resolve Qase run result statuses: %v", err)
		return
	}

	resolvedTotal := int64(0)
	for _, count := range statusCounts {
		resolvedTotal += count
	}

	if total != resolvedTotal || passed != statusCounts[qase.StatusPassed] {
		logrus.Warnf("Qase run stats do not match resolved result statuses: stats total=%d passed=%d, results total=%d passed=%d", total, passed, resolvedTotal, statusCounts[qase.StatusPassed])
		return
	}

	failed := statusCounts[qase.StatusFailed]
	exceededThresholds := statusCounts[qase.StatusExceededThresholds]
	if passed+failed+exceededThresholds != total {
		logrus.Warnf("Qase run result statuses include values that cannot be represented in the run stats summary: %v", statusCounts)
		return
	}

	// Percentage is undefined for an empty run; report 0 rather than dividing by zero.
	var passPercent float64
	if total > 0 {
		passPercent = float64(passed) / float64(total) * 100
	}

	runURL := fmt.Sprintf("https://app.qase.io/run/%s/dartboard/%d", projectID, runIDVal)

	fmt.Printf("QASE_RUN_TOTAL=%d\n", total)
	fmt.Printf("QASE_RUN_PASSED=%d\n", passed)
	fmt.Printf("QASE_RUN_FAILED=%d\n", failed)
	fmt.Printf("QASE_RUN_EXCEEDED_THRESHOLDS=%d\n", exceededThresholds)
	fmt.Printf("QASE_RUN_PASS_PERCENT=%.0f\n", passPercent)
	fmt.Printf("QASE_RUN_URL=%s\n", runURL)

	logrus.Infof("Qase run %d: %d/%d passed (%.0f%%)", runIDVal, passed, total, passPercent)
}