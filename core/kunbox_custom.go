package libbox

import (
	"sync"
	"sync/atomic"
)

// --- KunBox custom extensions ---

var kunBoxVersion = "1.0.0-kunbox"

// SetKunBoxVersion sets the version string returned by GetKunBoxVersion.
func SetKunBoxVersion(v string) { kunBoxVersion = v }

// GetKunBoxVersion returns the KunBox extension version.
func GetKunBoxVersion() string { return kunBoxVersion }

var connectionCount atomic.Int64

// IncrConnectionCount increments the active connection counter.
func IncrConnectionCount() { connectionCount.Add(1) }

// DecrConnectionCount decrements the active connection counter.
func DecrConnectionCount() { connectionCount.Add(-1) }

// GetConnectionCount returns the current active connection count.
func GetConnectionCount() int32 { return int32(connectionCount.Load()) }

var resetAllConnsFunc func(system bool)

// RegisterResetAllConnections registers the callback for reset.
func RegisterResetAllConnections(fn func(system bool)) { resetAllConnsFunc = fn }

// ResetAllConnections resets all connections.
func ResetAllConnections(system bool) {
	if resetAllConnsFunc != nil {
		resetAllConnsFunc(system)
	}
}

var closeAllTrackedFunc func() int32

// RegisterCloseAllTrackedConnections registers the callback.
func RegisterCloseAllTrackedConnections(fn func() int32) { closeAllTrackedFunc = fn }

// CloseAllTrackedConnections closes all tracked connections and returns count.
func CloseAllTrackedConnections() int32 {
	if closeAllTrackedFunc != nil {
		return closeAllTrackedFunc()
	}
	return 0
}

var recoverNetworkAutoFunc func() bool

// RegisterRecoverNetworkAuto registers the callback.
func RegisterRecoverNetworkAuto(fn func() bool) { recoverNetworkAutoFunc = fn }

// RecoverNetworkAuto attempts automatic network recovery.
func RecoverNetworkAuto() bool {
	if recoverNetworkAutoFunc != nil {
		return recoverNetworkAutoFunc()
	}
	return false
}

var checkNetworkRecoveryFunc func() bool

// RegisterCheckNetworkRecoveryNeeded registers the callback.
func RegisterCheckNetworkRecoveryNeeded(fn func() bool) { checkNetworkRecoveryFunc = fn }

// CheckNetworkRecoveryNeeded checks if network recovery is needed.
func CheckNetworkRecoveryNeeded() bool {
	if checkNetworkRecoveryFunc != nil {
		return checkNetworkRecoveryFunc()
	}
	return false
}

// Ensure sync import is used
var _ sync.Once
