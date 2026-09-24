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

	qaseClient = qase.SetupQaseClient()

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

	include := "stats"
	run, err := qaseClient.GetTestRun(context.Background(), projectID, runIDVal, &include)
	if err != nil {
		logrus.Fatalf("Failed to get test run: %v", err)
	}

	var total, passed, failed int64
	if stats := run.Stats; stats != nil {
		total = int64(stats.GetTotal())
		passed = int64(stats.GetPassed())
		failed = int64(stats.GetFailed())
	}

	statusCounts, err := qaseClient.GetRunResultStatusCounts(context.Background(), projectID, runIDVal)
	if err != nil {
		logrus.Fatalf("Unable to resolve Qase run result statuses: %v", err)
	}

	exceededThresholds := statusCounts[qase.StatusExceededThresholds]
	if count, ok := statusCounts[qase.StatusPassed]; ok && passed == 0 {
		passed = count
	}
	if count, ok := statusCounts[qase.StatusFailed]; ok && failed == 0 {
		failed = count
	}

	runURL := fmt.Sprintf("https://app.qase.io/run/%s/dashboard/%d", projectID, runIDVal)

	fmt.Printf("QASE_RUN_TOTAL=%d\n", total)
	fmt.Printf("QASE_RUN_PASSED=%d\n", passed)
	fmt.Printf("QASE_RUN_FAILED=%d\n", failed)
	fmt.Printf("QASE_RUN_EXCEEDED_THRESHOLDS=%d\n", exceededThresholds)
	fmt.Printf("QASE_RUN_URL=%s\n", runURL)

	logrus.Infof("Qase run %d: %d/%d passed", runIDVal, passed, total)
}