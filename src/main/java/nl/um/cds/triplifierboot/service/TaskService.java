package nl.um.cds.triplifierboot.service;

import nl.um.cds.triplifier.DatabaseInspector;
import nl.um.cds.triplifier.ForeignKeySpecification;
import nl.um.cds.triplifier.rdf.AnnotationFactory;
import nl.um.cds.triplifier.rdf.DataFactory;
import nl.um.cds.triplifier.rdf.OntologyFactory;
import nl.um.cds.triplifierboot.config.TaskProperties;
import nl.um.cds.triplifierboot.entity.TaskEntity;
import nl.um.cds.triplifierboot.repository.TaskRepository;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import org.eclipse.rdf4j.model.Statement;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import javax.transaction.Transactional;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

@Service
@EnableConfigurationProperties(TaskProperties.class)
public class TaskService {

    private static Logger logger = LoggerFactory.getLogger(TaskService.class);

    private TaskRepository taskRepository;

    private TaskProperties taskProperties;

    public TaskService(TaskRepository taskRepository, TaskProperties taskProperties) {
        this.taskRepository = taskRepository;
        this.taskProperties = taskProperties;
    }

    private String getTaskPath(String identifier){
        String taskPath = taskProperties.getWorkdir() + "/" + identifier;
        File directory = new File(taskPath);
        if (!directory.exists()) {
            directory.mkdirs();
        }
        return taskPath;
    }

    private String getTaskFilePath(String identifier, String filePath){
        return taskProperties.getWorkdir() + "/" + identifier + "/" + filePath;
    }

    public TaskEntity createTask(MultipartFile file) throws IOException {
        String identifier = FilenameUtils.removeExtension(file.getOriginalFilename());
        TaskEntity task = new TaskEntity();
        taskRepository.save(task);
        identifier = task.getId().toString();

        Path workDirTask = Paths.get(getTaskPath(identifier));
        logger.info("Clean workdir {}", workDirTask);
        deleteDir(workDirTask);
        Files.createDirectories(workDirTask);

        FileCopyUtils.copy(file.getInputStream(), new FileOutputStream(getTaskFilePath(identifier, file.getOriginalFilename())));

        taskRepository.save(task);
        return task;
    }

//    @Transactional
    public void runTask(TaskEntity task){
        task.setStatus(TaskEntity.Status.RUNNING);
        taskRepository.save(task);

        String identifier = task.getId().toString();
        String propertiesFilePath = taskProperties.getPropertiesFile();

        String workdir = getTaskPath(task.getId().toString());
        String ontologyFilePath = getOntologyFile(identifier).getAbsolutePath();
        String outputFilePath = getOutputFile(identifier).getAbsolutePath();

        Properties props = new Properties();


        props.setProperty("jdbc.url", "jdbc:relique:csv:" + workdir + "?fileExtension=.csv");
        props.setProperty("jdbc.user", "user");
        props.setProperty("jdbc.password", "pass");
        props.setProperty("jdbc.driver", "org.relique.jdbc.csv.CsvDriver");

        String ontologyUri = "http://ontology.local/" + task.getId().toString();
        String dataUri = "http://data.local/" + task.getId().toString() + "/";

        props.setProperty("repo.type", taskProperties.getSparqlType());
        props.setProperty("repo.url", taskProperties.getSparqlUrl());
        props.setProperty("repo.id", taskProperties.getSparqlDb());
        props.setProperty("repo.ontologyUri", ontologyUri);
        props.setProperty("repo.dataUri", dataUri);
        //props.setProperty("repo.user", "http://localhost:7200");
        //props.setProperty("repo.pass", "http://localhost:7200");

        OntologyFactory of = new OntologyFactory(ontologyUri + "/", props);
        DataFactory df = new DataFactory(of, props);
        AnnotationFactory af = new AnnotationFactory(props);

        try {
            logger.info("Start extracting ontology: " + System.currentTimeMillis());
            DatabaseInspector dbInspect = new DatabaseInspector(props);
            createOntology(dbInspect, of, ontologyFilePath);
            logger.info("Done extracting ontology: " + System.currentTimeMillis());
            logger.info("Ontology exported to " + ontologyFilePath);

            logger.info("Start extracting data: " + System.currentTimeMillis());
            df.convertData();
            logger.info("Start exporting data file: " + System.currentTimeMillis());
            df.exportData(outputFilePath);
            logger.info("Data exported to " + outputFilePath);
            logger.info("Done: " + System.currentTimeMillis());
        } catch (SQLException e) {
            logger.error("Could not connect to database with url " + props.getProperty("jdbc.url"));
            e.printStackTrace();
            task.setStatus(TaskEntity.Status.ERROR);
            task.setErrorMessage(e.getMessage().substring(0, Math.min(255, e.getMessage().length())));
        } catch (IOException e) {
            e.printStackTrace();
            task.setStatus(TaskEntity.Status.ERROR);
            task.setErrorMessage(e.getMessage().substring(0, Math.min(255, e.getMessage().length())));
        }
        if(!task.getStatus().equals(TaskEntity.Status.ERROR)){
            task.setStatus(TaskEntity.Status.COMPLETE);
        }
        taskRepository.saveAndFlush(task);
    }

    private void createOntology(DatabaseInspector dbInspect, OntologyFactory of, String ontologyOutputFilePath) throws SQLException, IOException {
        for(Map<String,String> tableName : dbInspect.getTableNames()) {
            logger.info("Table name: " + tableName);
            List<String> columns = dbInspect.getColumnNames(tableName.get("name"));
            List<String> primaryKeys = dbInspect.getPrimaryKeyColumns(tableName.get("catalog"), tableName.get("schema"), tableName.get("name"));
            List<ForeignKeySpecification> foreignKeys = dbInspect.getForeignKeyColumns(tableName.get("catalog"), tableName.get("schema"), tableName.get("name"));

            of.processTable(tableName.get("name"), columns, primaryKeys, foreignKeys, tableName.get("schema"), tableName.get("catalog"));
        }

        try {
            of.exportData(ontologyOutputFilePath);
        } catch (IOException e) {
            e.printStackTrace();
            throw e;
        }
    }


    public TaskEntity save(TaskEntity task) {
        return taskRepository.save(task);
    }

    public File getOntologyFile(String taskId) {
        String path = getTaskFilePath(taskId, taskProperties.getOntologyFile());
        return new File(path);
    }

    public File getOutputFile(String taskId) {
        String path = getTaskFilePath(taskId, taskProperties.getOutputFile());
        return new File(path);
    }

    public static void deleteDir(Path dir) throws IOException {
        logger.info("Delete dir={}", dir);
        Files.createDirectories(dir);
        Files.walk(dir)
                .map(Path::toFile)
                .forEach(File::delete);
    }

    public TaskEntity getTaskEntity(String identifier) {
        return taskRepository.getById(UUID.fromString(identifier));
    }

    @PostConstruct
    public void deleteTasks() {
        logger.info("Clean tasks in QUEUE");
        taskRepository.deleteAll();

        logger.info("Clean workdir={}", taskProperties.getWorkdir());
        try {
            deleteDir(Paths.get(taskProperties.getWorkdir()));
            Files.createDirectories(Path.of(taskProperties.getWorkdir()));
        } catch (IOException e) {
            logger.error("Error cleaning workdir={}", taskProperties.getWorkdir(), e);
        }
    }
}
