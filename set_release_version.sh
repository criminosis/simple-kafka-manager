#For use in Github Actions
raw="::set-output name=build_version::$(./mvnw help:evaluate -Dexpression=project.version -q -DforceStdout)"
echo "${raw/-SNAPSHOT/}"