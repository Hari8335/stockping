package config

import (
	"fmt"
	"strconv"
)

// Port validates configuration before the listener starts.
func Port(value string) (string, error) {
	if value == "" {
		return "8083", nil
	}
	port, err := strconv.Atoi(value)
	if err != nil || port < 1 || port > 65535 {
		return "", fmt.Errorf("PORT must be an integer between 1 and 65535")
	}
	return strconv.Itoa(port), nil
}
