package org.protege.editor.owl.server.base;

import edu.stanford.protege.metaproject.Manager;
import edu.stanford.protege.metaproject.api.*;
import edu.stanford.protege.metaproject.api.exception.*;
import org.apache.commons.io.FileUtils;
import org.protege.editor.owl.server.ServerActivator;
import org.protege.editor.owl.server.api.CommitBundle;
import org.protege.editor.owl.server.api.ServerLayer;
import org.protege.editor.owl.server.api.TransportHandler;
import org.protege.editor.owl.server.api.exception.AuthorizationException;
import org.protege.editor.owl.server.api.exception.OWLServerException;
import org.protege.editor.owl.server.api.exception.ServerServiceException;
import org.protege.editor.owl.server.versioning.ChangeHistoryImpl;
import org.protege.editor.owl.server.versioning.Commit;
import org.protege.editor.owl.server.versioning.InvalidHistoryFileException;
import org.protege.editor.owl.server.versioning.api.ChangeHistory;
import org.protege.editor.owl.server.versioning.api.DocumentRevision;
import org.protege.editor.owl.server.versioning.api.HistoryFile;
import org.protege.editor.owl.server.versioning.api.ServerDocument;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.*;

/**
 * The main server that acts as the end-point server where user requests to the
 * server get implemented.
 *
 * @author Josef Hardi <johardi@stanford.edu> <br>
 *         Stanford Center for Biomedical Informatics Research
 */
public class ProtegeServer extends ServerLayer {

    private final ServerConfiguration configuration;

    private final AuthenticationRegistry authenticationRegistry;
    private final UserRegistry userRegistry;
    private final ProjectRegistry projectRegistry;
    private final RoleRegistry roleRegistry;
    private final OperationRegistry operationRegistry;
    private final Policy policy;
    private final MetaprojectAgent metaprojectAgent;

    private File configurationFile;

    private TransportHandler transport;

    private static final MetaprojectFactory metaprojectFactory = Manager.getFactory();

    public ProtegeServer(ServerConfiguration configuration) {
        this.configuration = configuration;
        authenticationRegistry = configuration.getAuthenticationRegistry();
        userRegistry = configuration.getMetaproject().getUserRegistry();
        projectRegistry = configuration.getMetaproject().getProjectRegistry();
        roleRegistry = configuration.getMetaproject().getRoleRegistry();
        operationRegistry = configuration.getMetaproject().getOperationRegistry();
        policy = configuration.getMetaproject().getPolicy();
        metaprojectAgent = configuration.getMetaproject().getMetaprojectAgent();
        
        String configLocation = System.getProperty(ServerActivator.SERVER_CONFIGURATION_PROPERTY);
        configurationFile = new File(configLocation);
    }

    @Override
    public ServerConfiguration getConfiguration() {
        return configuration;
    }

    @Override
    public void createUser(AuthToken token, User newUser, Optional<SaltedPasswordDigest> password)
            throws AuthorizationException, ServerServiceException {
        synchronized (userRegistry) {
            try {
                metaprojectAgent.add(newUser);
                saveChanges();
            }
            catch (IdAlreadyInUseException e) {
                throw new ServerServiceException(e.getMessage(), e);
            }
        }
        if (password.isPresent()) {
            synchronized (authenticationRegistry) {
                try {
                    authenticationRegistry.add(newUser.getId(), password.get());
                }
                catch (IdAlreadyInUseException e) {
                    throw new ServerServiceException(e.getMessage(), e);
                }
                saveChanges();
            }
        }
    }

    @Override
    public void deleteUser(AuthToken token, UserId userId) throws AuthorizationException, ServerServiceException {
        synchronized (userRegistry) {
            try {
                metaprojectAgent.remove(userRegistry.get(userId));
                saveChanges();
            }
            catch (UnknownMetaprojectObjectIdException e) {
                throw new ServerServiceException(e.getMessage(), e);
            }
        }
    }

    @Override
    public void updateUser(AuthToken token, UserId userId, User updatedUser)
            throws AuthorizationException, ServerServiceException {
        synchronized (userRegistry) {
            try {
                userRegistry.update(userId, updatedUser);
                saveChanges();
            }
            catch (UnknownMetaprojectObjectIdException e) {
                throw new ServerServiceException(e.getMessage(), e);
            }
        }
    }

    @Override
    public ServerDocument createProject(AuthToken token, ProjectId projectId, Name projectName, Description description,
            UserId owner, Optional<ProjectOptions> options) throws AuthorizationException, ServerServiceException {
        ServerDocument serverDocument = null;
        try {
            HistoryFile historyFile = createHistoryFile(projectId.get(), projectName.get());
            synchronized (projectRegistry) {
                Project newProject = metaprojectFactory.getProject(projectId, projectName, description, historyFile, owner, options);
                try {
                    metaprojectAgent.add(newProject);
                    saveChanges();
                }
                catch (IdAlreadyInUseException e) {
                    throw new ServerServiceException(e.getMessage(), e);
                }
            }
            serverDocument = createServerDocument(historyFile);
        }
        catch (IOException e) {
            throw new ServerServiceException("Failed to create history file in remote server", e);
        }
        return serverDocument;
    }

    private HistoryFile createHistoryFile(String projectDir, String filename) throws IOException {
        String rootDir = configuration.getServerRoot().getAbsolutePath() + File.separator + projectDir;
        filename = filename.replaceAll("\\s+","_"); // to snake-case
        return HistoryFile.createNew(rootDir, filename);
    }

    private ServerDocument createServerDocument(HistoryFile historyFile) {
        final URI serverAddress = configuration.getHost().getUri();
        final Optional<Port> registryPort = configuration.getHost().getSecondaryPort();
        if (registryPort.isPresent()) {
            Port port = registryPort.get();
            return new ServerDocument(serverAddress, port.get(), historyFile);
        }
        else {
            return new ServerDocument(serverAddress, historyFile);
        }
    }

    @Override
    public void deleteProject(AuthToken token, ProjectId projectId, boolean includeFile)
            throws AuthorizationException, ServerServiceException {
        synchronized (projectRegistry) {
            try {
                Project project = projectRegistry.get(projectId);
                metaprojectAgent.remove(project);
                if (includeFile) {
                    HistoryFile historyFile = HistoryFile.openExisting(project.getFile().getAbsolutePath());
                    File projectDir = historyFile.getParentFile();
                    FileUtils.deleteDirectory(projectDir);
                }
                saveChanges();
            }
            catch (UnknownMetaprojectObjectIdException e) {
                throw new ServerServiceException(e.getMessage(), e);
            }
            catch (InvalidHistoryFileException e) {
                throw new ServerServiceException(e.getMessage(), e);
            }
            catch (IOException e) {
                throw new ServerServiceException(e.getMessage(), e);
            }
        }
    }

    @Override
    public void updateProject(AuthToken token, ProjectId projectId, Project updatedProject)
            throws AuthorizationException, ServerServiceException {
        synchronized (projectRegistry) {
            try {
                projectRegistry.update(projectId, updatedProject);
                saveChanges();
            }
            catch (UnknownMetaprojectObjectIdException e) {
                throw new ServerServiceException(e.getMessage(), e);
            }
        }
    }

    @Override
    public ServerDocument openProject(AuthToken token, ProjectId projectId)
            throws AuthorizationException, ServerServiceException {
        try {
            Project project = projectRegistry.get(projectId);
            final URI serverAddress = configuration.getHost().getUri();
            final Optional<Port> registryPort = configuration.getHost().getSecondaryPort();
            final String path = project.getFile().getAbsolutePath();
            if (registryPort.isPresent()) {
                Port port = registryPort.get();
                return new ServerDocument(serverAddress, port.get(), HistoryFile.openExisting(path));
            }
            else {
                return new ServerDocument(serverAddress, HistoryFile.openExisting(path));
            }
        }
        catch (UnknownMetaprojectObjectIdException e) {
            throw new ServerServiceException(e);
        }
        catch (InvalidHistoryFileException e) {
            throw new ServerServiceException("Unable to access history file in remote server", e);
        }
    }

    @Override
    public void createRole(AuthToken token, Role newRole) throws AuthorizationException, ServerServiceException {
        synchronized (roleRegistry) {
            try {
                metaprojectAgent.add(newRole);
                saveChanges();
            }
            catch (IdAlreadyInUseException e) {
                throw new ServerServiceException(e.getMessage(), e);
            }
        }
    }

    @Override
    public void deleteRole(AuthToken token, RoleId roleId) throws AuthorizationException, ServerServiceException {
        synchronized (roleRegistry) {
            try {
                metaprojectAgent.remove(roleRegistry.get(roleId));
                saveChanges();
            }
            catch (UnknownMetaprojectObjectIdException e) {
                throw new ServerServiceException(e.getMessage(), e);
            }
        }
    }

    @Override
    public void updateRole(AuthToken token, RoleId roleId, Role updatedRole)
            throws AuthorizationException, ServerServiceException {
        synchronized (roleRegistry) {
            try {
                roleRegistry.update(roleId, updatedRole);
                saveChanges();
            }
            catch (UnknownMetaprojectObjectIdException e) {
                throw new ServerServiceException(e.getMessage(), e);
            }
        }
    }

    @Override
    public void createOperation(AuthToken token, Operation newOperation)
            throws AuthorizationException, ServerServiceException {
        synchronized (operationRegistry) {
            try {
                metaprojectAgent.add(newOperation);
                saveChanges();
            }
            catch (IdAlreadyInUseException e) {
                throw new ServerServiceException(e.getMessage(), e);
            }
        }
    }

    @Override
    public void deleteOperation(AuthToken token, OperationId operationId)
            throws AuthorizationException, ServerServiceException {
        synchronized (operationRegistry) {
            try {
                metaprojectAgent.remove(operationRegistry.get(operationId));
                saveChanges();
            }
            catch (UnknownMetaprojectObjectIdException e) {
                throw new ServerServiceException(e.getMessage(), e);
            }
        }
    }

    @Override
    public void updateOperation(AuthToken token, OperationId operationId, Operation updatedOperation)
            throws AuthorizationException, ServerServiceException {
        synchronized (operationRegistry) {
            try {
                operationRegistry.update(operationId, updatedOperation);
                saveChanges();
            }
            catch (UnknownMetaprojectObjectIdException e) {
                throw new ServerServiceException(e.getMessage(), e);
            }
        }
    }

    @Override
    public void assignRole(AuthToken token, UserId userId, ProjectId projectId, RoleId roleId)
            throws AuthorizationException, ServerServiceException {
        synchronized (policy) {
            policy.add(roleId, projectId, userId);
            saveChanges();
        }
    }

    @Override
    public void retractRole(AuthToken token, UserId userId, ProjectId projectId, RoleId roleId)
            throws AuthorizationException, ServerServiceException {
        synchronized (policy) {
            policy.remove(userId, projectId, roleId);
            saveChanges();
        }
    }

    @Override
    public Host getHost(AuthToken token) throws AuthorizationException, ServerServiceException {
        return configuration.getHost();
    }

    @Override
    public void setHostAddress(AuthToken token, URI hostAddress) throws AuthorizationException, ServerServiceException {
        synchronized (configuration) {
            Optional<Port> secondaryPort = getHost(token).getSecondaryPort();
            Host updatedHost = metaprojectFactory.getHost(hostAddress, secondaryPort);
            configuration.setHost(updatedHost);
            saveChanges();
        }
    }

    @Override
    public void setSecondaryPort(AuthToken token, int portNumber)
            throws AuthorizationException, ServerServiceException {
        synchronized (configuration) {
            URI hostAddress = getHost(token).getUri();
            Optional<Port> secondaryPort = Optional.empty();
            if (portNumber > 0) {
                secondaryPort = Optional.of(metaprojectFactory.getPort(portNumber));
            }
            Host updatedHost = metaprojectFactory.getHost(hostAddress, secondaryPort);
            configuration.setHost(updatedHost);
            saveChanges();
        }
    }

    @Override
    public String getRootDirectory(AuthToken token) throws AuthorizationException, ServerServiceException {
        return configuration.getServerRoot().toString();
    }

    @Override
    public void setRootDirectory(AuthToken token, String rootDirectory)
            throws AuthorizationException, ServerServiceException {
        synchronized (configuration) {
            configuration.setServerRoot(new File(rootDirectory));
            saveChanges();
        }
    }

    @Override
    public Map<String, String> getServerProperties(AuthToken token)
            throws AuthorizationException, ServerServiceException {
        return configuration.getProperties();
    }

    @Override
    public void setServerProperty(AuthToken token, String property, String value)
            throws AuthorizationException, ServerServiceException {
        synchronized (configuration) {
            configuration.addProperty(property, value);
            saveChanges();
        }
    }

    @Override
    public void unsetServerProperty(AuthToken token, String property)
            throws AuthorizationException, ServerServiceException {
        synchronized (configuration) {
            configuration.removeProperty(property);
            saveChanges();
        }
    }

    @Override
    public ChangeHistory commit(AuthToken token, ProjectId projectId, CommitBundle commitBundle)
            throws AuthorizationException, ServerServiceException {
        DocumentRevision baseRevision = commitBundle.getBaseRevision();
        ChangeHistory changeHistory = ChangeHistoryImpl.createEmptyChangeHistory(baseRevision);
        for (Commit commit : commitBundle.getCommits()) {
            changeHistory.addRevision(commit.getMetadata(), commit.getChanges());
        }
        return changeHistory;
    }

    @Override
    public void setTransport(TransportHandler transport) throws OWLServerException {
        this.transport = transport;
    }

    @Override
    public List<User> getAllUsers(AuthToken token) throws AuthorizationException, ServerServiceException {
        return new ArrayList<>(userRegistry.getEntries());
    }

    @Override
    public List<Project> getProjects(AuthToken token, UserId userId)
            throws AuthorizationException, ServerServiceException {
        try {
            return new ArrayList<>(metaprojectAgent.getProjects(userId));
        }
        catch (UserNotInPolicyException e) {
            throw new ServerServiceException(e.getMessage(), e.getCause());
        }
    }

    @Override
    public List<Project> getAllProjects(AuthToken token) throws AuthorizationException, ServerServiceException {
        return new ArrayList<>(projectRegistry.getEntries());
    }

    @Override
    public Map<ProjectId, List<Role>> getRoles(AuthToken token, UserId userId)
            throws AuthorizationException, ServerServiceException {
        Map<ProjectId, List<Role>> roleMap = new HashMap<>();
        for (Project project : getAllProjects(token)) {
            roleMap.put(project.getId(), getRoles(token, userId, project.getId()));
        }
        return roleMap;
    }

    @Override
    public List<Role> getRoles(AuthToken token, UserId userId, ProjectId projectId)
            throws AuthorizationException, ServerServiceException {
        try {
            return new ArrayList<>(metaprojectAgent.getRoles(userId, projectId));
        }
        catch (UserNotInPolicyException | ProjectNotInPolicyException e) {
            throw new ServerServiceException(e.getMessage(), e);
        }
    }

    @Override
    public List<Role> getAllRoles(AuthToken token) throws AuthorizationException, ServerServiceException {
        return new ArrayList<>(roleRegistry.getEntries());
    }

    @Override
    public Map<ProjectId, List<Operation>> getOperations(AuthToken token, UserId userId)
            throws AuthorizationException, ServerServiceException {
        Map<ProjectId, List<Operation>> operationMap = new HashMap<>();
        for (Project project : getAllProjects(token)) {
            operationMap.put(project.getId(), getOperations(token, userId, project.getId()));
        }
        return operationMap;
    }

    @Override
    public List<Operation> getOperations(AuthToken token, UserId userId, ProjectId projectId)
            throws AuthorizationException, ServerServiceException {
        try {
            return new ArrayList<>(metaprojectAgent.getOperations(userId, projectId));
        }
        catch (UserNotInPolicyException | ProjectNotInPolicyException e) {
            throw new ServerServiceException(e.getMessage(), e);
        }
    }

    @Override
    public List<Operation> getOperations(AuthToken token, RoleId roleId)
            throws AuthorizationException, ServerServiceException {
        try {
            return new ArrayList<>(metaprojectAgent.getOperations(roleRegistry.get(roleId)));
        }
        catch (UnknownMetaprojectObjectIdException e) {
            throw new ServerServiceException(e.getMessage(), e);
        }
    }

    @Override
    public List<Operation> getAllOperations(AuthToken token) throws AuthorizationException, ServerServiceException {
        return new ArrayList<>(operationRegistry.getEntries());
    }

    @Override
    public boolean isOperationAllowed(AuthToken token, OperationId operationId, ProjectId projectId, UserId userId)
            throws AuthorizationException, ServerServiceException {
        return metaprojectAgent.isOperationAllowed(operationId, projectId, userId);
    }

    private void saveChanges() throws ServerServiceException {
        synchronized (configurationFile) {
            try {
                Manager.getConfigurationManager().saveServerConfiguration(configurationFile);
            }
            catch (ServerConfigurationNotLoadedException | IOException e) {
                throw new ServerServiceException(e.getMessage(), e);
            }
        }
    }
}
