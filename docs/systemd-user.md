# User Service

Pickle can run as a normal user service. Install the binary somewhere stable,
then create `~/.config/systemd/user/pickle.service`:

```ini
[Unit]
Description=Pickle agent inbox daemon

[Service]
ExecStart=%h/.local/bin/pickle serve --listen 0.0.0.0:8787
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
```

Enable it:

```bash
systemctl --user daemon-reload
systemctl --user enable --now pickle.service
systemctl --user status pickle.service
```

Use `pickle token` to copy the Android bearer token. Keep the listener on
Tailscale or another private network.
