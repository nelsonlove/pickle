package model

import (
	"crypto/rand"
	"encoding/base32"
	"strings"
	"time"
)

func NewRequestID() (string, error) {
	buf := make([]byte, 10)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	randomPart := strings.ToLower(strings.TrimRight(base32.StdEncoding.EncodeToString(buf), "="))
	return "req_" + time.Now().UTC().Format("20060102t150405") + "_" + randomPart, nil
}
