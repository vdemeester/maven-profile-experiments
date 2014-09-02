package com.eileo.tools;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Profile;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.profiles.DefaultMavenProfilesBuilder;
import org.apache.maven.profiles.DefaultProfileManager;
import org.apache.maven.profiles.MavenProfilesBuilder;
import org.apache.maven.profiles.ProfileManager;
import org.apache.maven.profiles.ProfilesConversionUtils;
import org.apache.maven.profiles.ProfilesRoot;
import org.apache.maven.profiles.activation.ProfileActivationException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.SettingsUtils;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import static org.apache.maven.plugins.annotations.LifecyclePhase.VALIDATE;

@Mojo(name = "activate-profile", defaultPhase = VALIDATE, requiresProject = false)
class ActivateProfileMojo extends AbstractMojo {
	@Parameter(defaultValue = "${reactorProjects}", required = true, readonly = true)
	private List<MavenProject> projects;

	@Component
	private MavenSession session;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		for (MavenProject project : this.projects) {
			ProfileManager pm = new DefaultProfileManager(this.session.getContainer(), this.session.getExecutionProperties());

			loadProfiles(project, pm);

			getLog().info(" ======== ======== ======== ======== ======== ======== ");
			getLog().info(" Properties ");
			getLog().info(" ======== ======== ======== ======== ======== ======== ");

			Set<Map.Entry<Object, Object>> entries = project.getProperties().entrySet();
			for (Map.Entry<Object, Object> entry : entries) {
				getLog().info(String.format("%s: %s", entry.getKey(), entry.getValue()));
			}

			if (project.getProperties().getProperty("ZEN.release") != null) {
				pm.explicitlyActivate("ZEN");

				Profile zen = getProfileById(pm, "ZEN");

				addActiveProfile(pm, project, zen);

				MavenProject parent = project.getParent();
				while (parent != null) {
					parent.getProperties().put("ZEN.release", "true");

					addActiveProfile(pm, parent, zen);

					parent = parent.getParent();
				}
			}
		}
	}

	private static Profile getProfileById(ProfileManager pm, String id) {
		return (Profile) pm.getProfilesById().get(id);
	}

	private static void addActiveProfile(ProfileManager pm, MavenProject project, Profile profile) {
		profile.getActivation().setActiveByDefault(true);

		pm.addProfile(profile);
		pm.explicitlyActivate(profile.getId());
	}

	private void loadProfiles(MavenProject project, ProfileManager pm) throws MojoExecutionException {
		try {
			loadProjectExternalProfiles(pm, project.getBasedir());
		} catch (ProfileActivationException e) {
			throw new MojoExecutionException("Error obtaining external Profiles:" + e.getMessage(), e);
		}

		loadSettingsProfiles(pm, this.session.getSettings());

		loadProjectPomProfiles(pm, project);
	}

	/**
	 * Loads up external Profiles using {@code profiles.xml} (if any) located in the current project's {@code
	 * ${basedir}}.
	 *
	 * @param profileManager ProfileManager instance to use to load profiles from external Profiles.
	 * @param projectDir location of the current project, could be null.
	 *
	 * @throws ProfileActivationException, if there was an error loading profiles.
	 */
	private void loadProjectExternalProfiles(ProfileManager profileManager, File projectDir) throws ProfileActivationException {
		if (projectDir == null) {
			return;
		}

		if (getLog().isDebugEnabled()) {
			getLog().debug("Attempting to read profiles from external profiles.xml...");
		}

		try {
			MavenProfilesBuilder profilesBuilder = new DefaultMavenProfilesBuilder();
			ProfilesRoot root = profilesBuilder.buildProfiles(projectDir);
			if (root != null) {
				List<org.apache.maven.profiles.Profile> profiles = root.getProfiles();
				for (org.apache.maven.profiles.Profile rawProfile : profiles) {
					Profile converted = ProfilesConversionUtils.convertFromProfileXmlProfile(rawProfile);
					profileManager.addProfile(converted);
					profileManager.explicitlyActivate(converted.getId());
				}
			} else if (getLog().isDebugEnabled()) {
				getLog().debug("ProfilesRoot was found to be NULL");
			}
		} catch (IOException e) {
			throw new ProfileActivationException("Cannot read profiles.xml resource from directory: "
			                                     + projectDir, e);
		} catch (XmlPullParserException e) {
			throw new ProfileActivationException("Cannot parse profiles.xml resource from directory: "
			                                     + projectDir, e);
		}
	}

	/**
	 * Load profiles from {@code pom.xml}.
	 *
	 * @param profilesManager not null
	 * @param project could be null
	 */
	private void loadProjectPomProfiles(ProfileManager profilesManager, MavenProject project) {
		if (project == null) {
			// shouldn't happen as this mojo requires a project
			if (getLog().isDebugEnabled()) {
				getLog().debug("No pom.xml found to read Profiles from.");
			}

			return;
		}

		if (getLog().isDebugEnabled()) {
			getLog().debug("Attempting to read profiles from pom.xml...");
		}

		// Attempt to obtain the list of profiles from pom.xml
		List<Profile> profiles = project.getModel().getProfiles();
		for (Profile profile : profiles) {
			profilesManager.addProfile(profile);
			profilesManager.explicitlyActivate(profile.getId());
		}

		MavenProject parent = project.getParent();
		while (parent != null) {
			List<Profile> profiles2 = parent.getModel().getProfiles();
			for (Profile profile : profiles2) {
				profilesManager.addProfile(profile);
				profilesManager.explicitlyActivate(profile.getId());
			}

			parent = parent.getParent();
		}
	}

	/**
	 * Load profiles from {@code settings.xml}.
	 *
	 * @param profileManager not null
	 * @param settings could be null
	 */
	private void loadSettingsProfiles(ProfileManager profileManager, Settings settings) {
		if (settings == null) {
			if (getLog().isDebugEnabled()) {
				getLog().debug("No settings.xml detected.");
			}

			return;
		}

		if (getLog().isDebugEnabled()) {
			getLog().debug("Attempting to read profiles from settings.xml...");
		}

		List<org.apache.maven.settings.Profile> profiles = settings.getProfiles();
		for (org.apache.maven.settings.Profile rawProfile : profiles) {
			Profile profile = SettingsUtils.convertFromSettingsProfile(rawProfile);
			profileManager.addProfile(profile);
			profileManager.explicitlyActivate(profile.getId());
		}
	}
}
