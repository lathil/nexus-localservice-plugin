package com.ptoceti.nexus3.plugin.localservice;

public class PathUtils {

    public static String calculatePath( String groupId, String  artifactId, String baseVersion, String version, String classifier, String extension) {
        StringBuilder path = new StringBuilder("/");
        path.append(groupId.replaceAll("(?m)(.)\\.", "$1/")); // replace all '.' except the first char
        path.append("/");
        path.append(artifactId);
        path.append("/");
        path.append(baseVersion);
        path.append("/");
        path.append(artifactId);
        path.append("-");
        path.append(version);
        if (classifier != null && classifier.trim().length() > 0) {
            path.append("-");
            path.append(classifier);
        }
        if (extension != null) {
            path.append(".");
            path.append(extension);
        }

        return path.toString();
    }

}
