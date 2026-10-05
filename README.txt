Endpoint Checker - folder layout

APP/          What you run (and the only folder you need to copy to another computer).
              Windows: double-click "Endpoint Checker.bat"
              Linux:   ./endpoint-checker.sh   (first time: chmod +x endpoint-checker.sh)
                       sh install-linux-menu.sh  adds it to the applications menu
              Your saved endpoints and history live in APP/endpoint-checker-data.json.
              Stop the program with the Quit button in the page.

JAVA/         Source code. Open JAVA/pom.xml in IntelliJ.
              Maven -> Lifecycle -> package builds the jar and copies it into ../APP automatically.
              (Quit the running app first on Windows, otherwise the jar is locked.)

dockerFiles/  OPTIONAL - only for running on a NAS/server with Docker + Caddy. Not needed otherwise.
              Copy .env.example to .env, set the domain, put a password hash in Caddyfile,
              then:  docker compose up -d --build

Updating: replace only APP/endpoint-checker.jar, never delete the whole APP folder
(it contains your data file).
