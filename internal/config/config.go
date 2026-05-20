package config

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
)

type Config struct {
	DataPath string `json:"data_path"`
	APIURL   string `json:"api_url"`
	Token    string `json:"token"`
}

func ConfigDir() string {
	if v := os.Getenv("WICKLE_CONFIG_HOME"); v != "" {
		return v
	}
	if runtime.GOOS == "windows" {
		if v := os.Getenv("APPDATA"); v != "" {
			return filepath.Join(v, "Wickle")
		}
	}
	if runtime.GOOS == "darwin" {
		if home, err := os.UserHomeDir(); err == nil {
			return filepath.Join(home, "Library", "Application Support", "wickle")
		}
	}
	if v := os.Getenv("XDG_CONFIG_HOME"); v != "" {
		return filepath.Join(v, "wickle")
	}
	if home, err := os.UserHomeDir(); err == nil {
		return filepath.Join(home, ".config", "wickle")
	}
	return "."
}

func DataDir() string {
	if v := os.Getenv("WICKLE_DATA_HOME"); v != "" {
		return v
	}
	if runtime.GOOS == "windows" {
		if v := os.Getenv("LOCALAPPDATA"); v != "" {
			return filepath.Join(v, "Wickle")
		}
	}
	if runtime.GOOS == "darwin" {
		if home, err := os.UserHomeDir(); err == nil {
			return filepath.Join(home, "Library", "Application Support", "wickle")
		}
	}
	if v := os.Getenv("XDG_DATA_HOME"); v != "" {
		return filepath.Join(v, "wickle")
	}
	if home, err := os.UserHomeDir(); err == nil {
		return filepath.Join(home, ".local", "share", "wickle")
	}
	return "."
}

func Path() string {
	return filepath.Join(ConfigDir(), "config.json")
}

func Default() Config {
	return Config{
		DataPath: filepath.Join(DataDir(), "wickle.sqlite"),
		APIURL:   "http://127.0.0.1:8787",
	}
}

func Load() (Config, error) {
	cfg := Default()
	b, err := os.ReadFile(Path())
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return cfg, nil
		}
		return Config{}, err
	}
	if err := json.Unmarshal(b, &cfg); err != nil {
		return Config{}, fmt.Errorf("parse %s: %w", Path(), err)
	}
	if cfg.DataPath == "" {
		cfg.DataPath = Default().DataPath
	}
	if cfg.APIURL == "" {
		cfg.APIURL = Default().APIURL
	}
	return cfg, nil
}

func Save(cfg Config) error {
	if err := os.MkdirAll(ConfigDir(), 0o700); err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(cfg.DataPath), 0o700); err != nil {
		return err
	}
	b, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(Path(), append(b, '\n'), 0o600)
}

func Ensure() (Config, bool, error) {
	cfg, err := Load()
	if err != nil {
		return Config{}, false, err
	}
	created := false
	if cfg.Token == "" {
		token, err := randomToken()
		if err != nil {
			return Config{}, false, err
		}
		cfg.Token = token
		created = true
	}
	if _, err := os.Stat(Path()); errors.Is(err, os.ErrNotExist) {
		created = true
	}
	if created {
		if err := Save(cfg); err != nil {
			return Config{}, false, err
		}
	}
	return cfg, created, nil
}

func randomToken() (string, error) {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(buf), nil
}
