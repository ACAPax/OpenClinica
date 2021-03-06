package org.akaza.openclinica.controller;

import static core.org.akaza.openclinica.dao.hibernate.multitenant.CurrentTenantIdentifierResolverImpl.CURRENT_TENANT_ID;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.sql.DataSource;

import core.org.akaza.openclinica.domain.datamap.Study;
import core.org.akaza.openclinica.bean.core.Role;
import core.org.akaza.openclinica.bean.extract.ArchivedDatasetFileBean;
import core.org.akaza.openclinica.bean.extract.DatasetBean;
import core.org.akaza.openclinica.bean.extract.ExtractPropertyBean;
import core.org.akaza.openclinica.bean.login.StudyUserRoleBean;
import core.org.akaza.openclinica.bean.login.UserAccountBean;
import core.org.akaza.openclinica.dao.core.CoreResources;
import core.org.akaza.openclinica.dao.extract.ArchivedDatasetFileDAO;
import core.org.akaza.openclinica.dao.extract.DatasetDAO;
import core.org.akaza.openclinica.domain.enumsupport.JobStatus;
import core.org.akaza.openclinica.i18n.core.LocaleResolver;
import core.org.akaza.openclinica.i18n.util.ResourceBundleProvider;
import core.org.akaza.openclinica.job.*;
import core.org.akaza.openclinica.service.PermissionService;
import core.org.akaza.openclinica.service.extract.ExtractUtils;
import core.org.akaza.openclinica.service.extract.XsltTriggerService;
import core.org.akaza.openclinica.web.SQLInitServlet;
import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.commons.lang.StringUtils;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.quartz.JobDetailFactoryBean;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

@Controller("extractController")
@RequestMapping("/extract")
public class ExtractController {
    @Autowired
    @Qualifier("sidebarInit")
    private SidebarInit sidebarInit;

    @Autowired
    @Qualifier("dataSource")
    private BasicDataSource dataSource;

    private DatasetDAO datasetDao;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private ApplicationContext applicationContext;

    public static String TRIGGER_GROUP_NAME = "XsltTriggers";
    protected final Logger logger = LoggerFactory.getLogger(getClass().getName());

    public ExtractController() {

    }

    /**
     * process the page from whence you came, i.e. extract a dataset
     * @param id, the id of the extract properties bean, gained from Core Resources
     * @param datasetId, the id of the dataset, found through DatasetDAO
     * @param request, http request
     * @return model map, but more importantly, creates a quartz job which runs right away and generates all output there
     */
    @RequestMapping(method = RequestMethod.GET)
    public ModelMap processSubmit(@RequestParam("id") String id,
                                  @RequestParam("datasetId") String datasetId, HttpServletRequest request, HttpServletResponse response) {
        if (!mayProceed(request)) {
            try {
                response.sendRedirect(request.getContextPath() + "/MainMenu?message=authentication_failed");
            } catch (Exception e) {
                logger.error("Error in redirecting the response: ", e);
            }
            return null;
        }

        ModelMap map = new ModelMap();
        ResourceBundleProvider.updateLocale(LocaleResolver.getLocale(request));
        // String datasetId = (String)request.getAttribute("datasetId");
        // String id = (String)request.getAttribute("id");
        logger.debug("found both id " + id + " and dataset " + datasetId);
        ExtractUtils extractUtils = new ExtractUtils();
        // get extract id
        // get dataset id
        // if id is a number and dataset id is a number ...
        datasetDao = new DatasetDAO(dataSource);
        UserAccountBean userBean = (UserAccountBean) request.getSession().getAttribute("userBean");
        CoreResources cr = new CoreResources();


        ExtractPropertyBean epBean = cr.findExtractPropertyBeanById(new Integer(id).intValue(), datasetId);

        DatasetBean dsBean = (DatasetBean) datasetDao.findByPK(new Integer(datasetId).intValue());
        // set the job in motion
        String[] files = epBean.getFileName();
        String exportFileName;
        int cnt = 0;
        SimpleTrigger simpleTrigger;
        //TODO: if files and export names size is not same... throw an error
        dsBean.setName(dsBean.getName().replaceAll(" ", "_"));
        String[] exportFiles = epBean.getExportFileName();
        String pattern = "yyyy" + File.separator + "MM" + File.separator + "dd" + File.separator + "HHmmssSSS" + File.separator;
        SimpleDateFormat sdfDir = new SimpleDateFormat(pattern);
        int i = 0;
        String[] temp = new String[exportFiles.length];
        //JN: The following logic is for comma separated variables, to avoid the second file be treated as a old file and deleted.
        while (i < exportFiles.length) {
            temp[i] = resolveVars(exportFiles[i], dsBean, SQLInitServlet.getField("filePath"), extractUtils);
            i++;
        }
        epBean.setDoNotDelFiles(temp);
        epBean.setExportFileName(temp);

        XsltTriggerService xsltService = new XsltTriggerService();

        // TODO get a user bean somehow?
        String generalFileDir = SQLInitServlet.getField("filePath");

        generalFileDir = generalFileDir + "datasets" + File.separator + dsBean.getId() + File.separator + sdfDir.format(new java.util.Date());

        exportFileName = epBean.getExportFileName()[cnt];


        // need to set the dataset path here, tbh
        logger.debug("found odm xml file path " + generalFileDir);
        // next, can already run jobs, translations, and then add a message to be notified later
        //JN all the properties need to have the variables...
        String xsltPath = SQLInitServlet.getField("filePath") + "xslt" + File.separator + files[cnt];
        String endFilePath = epBean.getFileLocation();
        endFilePath = getEndFilePath(endFilePath, dsBean, sdfDir, SQLInitServlet.getField("filePath"), extractUtils);
        //  exportFileName = resolveVars(exportFileName,dsBean,sdfDir);
        if (epBean.getPostProcExportName() != null) {
            //String preProcExportPathName = getEndFilePath(epBean.getPostProcExportName(),dsBean,sdfDir);
            String preProcExportPathName = resolveVars(epBean.getPostProcExportName(), dsBean, SQLInitServlet.getField("filePath"), extractUtils);
            epBean.setPostProcExportName(preProcExportPathName);
        }
        if (epBean.getPostProcLocation() != null) {
            String prePocLoc = getEndFilePath(epBean.getPostProcLocation(), dsBean, sdfDir, SQLInitServlet.getField("filePath"), extractUtils);
            epBean.setPostProcLocation(prePocLoc);
        }
        setAllProps(epBean, dsBean, extractUtils);
        // also need to add the status fields discussed w/ cc:
        // result code, user message, optional URL, archive message, log file message
        // asdf table: sort most recent at top
        logger.debug("found xslt file name " + xsltPath);
        Scheduler jobScheduler = getSchemaScheduler(request);

        ArchivedDatasetFileBean archivedDatasetFileBean = new ArchivedDatasetFileBean();
        archivedDatasetFileBean.setStatus(JobStatus.IN_QUEUE.name());
        archivedDatasetFileBean.setFormat(epBean.getFormatDescription());
        archivedDatasetFileBean.setOwnerId(userBean.getId());
        archivedDatasetFileBean.setDatasetId(dsBean.getId());
        archivedDatasetFileBean.setDateCreated(new Date());
        archivedDatasetFileBean.setExportFormatId(1);
        archivedDatasetFileBean.setFileReference(null);
        archivedDatasetFileBean.setJobUuid(UUID.randomUUID().toString());
        archivedDatasetFileBean.setJobExecutionUuid(UUID.randomUUID().toString());
        archivedDatasetFileBean.setJobType("Manual");

        ArchivedDatasetFileDAO archivedDatasetFileDAO = new ArchivedDatasetFileDAO(dataSource);
        archivedDatasetFileBean = (ArchivedDatasetFileBean) archivedDatasetFileDAO.create(archivedDatasetFileBean);


        // String xmlFilePath = generalFileDir + ODMXMLFileName;
        simpleTrigger = xsltService.generateXsltTrigger(jobScheduler, xsltPath,
                generalFileDir, // xml_file_path
                endFilePath + File.separator,
                exportFileName,
                dsBean.getId(),
                epBean,
                userBean,
                LocaleResolver.getLocale(request).getLanguage(),
                cnt,
                SQLInitServlet.getField("filePath") + "xslt",
                this.TRIGGER_GROUP_NAME,
                (Study) request.getSession().getAttribute("publicStudy"),
                (Study) request.getSession().getAttribute("study"),
                archivedDatasetFileBean);
        // System.out.println("just set locale: " + LocaleResolver.getLocale(request).getLanguage());

        cnt++;

        //WebApplicationContext context = ContextLoader.getCurrentWebApplicationContext();
        JobDetailFactoryBean jobDetailFactoryBean = applicationContext.getBean(JobDetailFactoryBean.class, simpleTrigger, this.TRIGGER_GROUP_NAME);


        try {
            Date dateStart = jobScheduler.scheduleJob(jobDetailFactoryBean.getObject(), simpleTrigger);
            logger.debug("== found job date: " + dateStart.toString());

        } catch (SchedulerException se) {
            logger.error("Error while accesssing job date: ", se);
        }

        request.setAttribute("datasetId", datasetId);
        // set the job name here in the user's session, so that we can ping the scheduler to pull it out later
        if (jobDetailFactoryBean != null)
            request.getSession().setAttribute("jobName", jobDetailFactoryBean.getObject().getKey().getName());
        if (simpleTrigger != null)
            request.getSession().setAttribute("groupName", this.TRIGGER_GROUP_NAME);

        request.getSession().setAttribute("datasetId", new Integer(dsBean.getId()));
        return map;
    }

    private Scheduler getSchemaScheduler(HttpServletRequest request) {
        Scheduler jobScheduler = null;
        if (request.getAttribute(CURRENT_TENANT_ID) != null) {
            String schema = (String) request.getAttribute(CURRENT_TENANT_ID);
            if (StringUtils.isNotEmpty(schema) &&
                    (schema.equalsIgnoreCase("public") != true)) {
                try {
                    jobScheduler = (Scheduler) applicationContext.getBean(schema);
                    logger.debug("Existing schema scheduler found:" + schema);
                } catch (NoSuchBeanDefinitionException e) {
                    createSchedulerFactoryBean(schema);
                    try {
                        jobScheduler = (Scheduler) applicationContext.getBean(schema);
                    } catch (BeansException e1) {
                        logger.error("Bean for scheduler is not able to accessed after crexating scheduled factory bean: ", e1);

                    }
                } catch (BeansException e) {
                    logger.error("Bean for scheduler is not able to accessed: ", e);

                }
            }
        }
        return jobScheduler;
    }

    public void createSchedulerFactoryBean(String schema) {
        logger.debug("Creating a new schema scheduler:" + schema);
        OpenClinicaSchedulerFactoryBean sFBean = new OpenClinicaSchedulerFactoryBean();
        sFBean.setSchedulerName(schema);
        Properties properties = new Properties();
        AutowiringSpringBeanJobFactory jobFactory = new AutowiringSpringBeanJobFactory();
        jobFactory.setApplicationContext(applicationContext);
        sFBean.setJobFactory(jobFactory);
        sFBean.setDataSource((DataSource) applicationContext.getBean("dataSource"));
        sFBean.setTransactionManager((PlatformTransactionManager) applicationContext.getBean("transactionManager"));
        sFBean.setApplicationContext(applicationContext);
        sFBean.setApplicationContextSchedulerContextKey("applicationContext");
        sFBean.setGlobalJobListeners(new JobExecutionExceptionListener());
        sFBean.setGlobalTriggerListeners(new JobTriggerListener());

        // use global Quartz properties
        properties.setProperty("org.quartz.jobStore.misfireThreshold",
                CoreResources.getField("org.quartz.jobStore.misfireThreshold"));
        properties.setProperty("org.quartz.jobStore.class",
                CoreResources.getField("org.quartz.jobStore.class"));
        properties.setProperty("org.quartz.jobStore.driverDelegateClass",
                CoreResources.getField("org.quartz.jobStore.driverDelegateClass"));
        properties.setProperty("org.quartz.jobStore.useProperties",
                CoreResources.getField("org.quartz.jobStore.useProperties"));
        properties.setProperty("org.quartz.jobStore.tablePrefix", schema + "." +
                CoreResources.getField("org.quartz.jobStore.tablePrefix"));
        properties.setProperty("org.quartz.threadPool.class",
                CoreResources.getField("org.quartz.threadPool.class"));
        properties.setProperty("org.quartz.threadPool.threadCount",
                CoreResources.getField("org.quartz.threadPool.threadCount"));
        properties.setProperty("org.quartz.threadPool.threadPriority",
                CoreResources.getField("org.quartz.threadPool.threadPriority"));
        sFBean.setQuartzProperties(properties);
        try {
            sFBean.afterPropertiesSet();
        } catch (Exception e) {
            logger.error("Error creating the scheduler bean:" + schema, e.getMessage(), e);
            return;
        }
        sFBean.start();
        ConfigurableListableBeanFactory beanFactory = ((ConfigurableApplicationContext) applicationContext).getBeanFactory();
        beanFactory.registerSingleton(schema, sFBean);
    }

    private ExtractPropertyBean setAllProps(ExtractPropertyBean epBean, DatasetBean dsBean, ExtractUtils extractUtils) {
        return extractUtils.setAllProps(epBean, dsBean, SQLInitServlet.getField("filePath"));


    }

    /**
     * for dateTimePattern, the directory structure is created. "yyyy" + File.separator + "MM" + File.separator + "dd" + File.separator,
     * to resolve location
     * @param filePath
     * @param extractUtils
     */
    private String getEndFilePath(String endFilePath, DatasetBean dsBean, SimpleDateFormat sdfDir, String filePath, ExtractUtils extractUtils) {
        return extractUtils.getEndFilePath(endFilePath, dsBean, sdfDir, filePath);
    }

    /**
     * Returns the datetime based on pattern :"yyyy-MM-dd-HHmmssSSS", typically for resolving file name
     * @param endFilePath
     * @param dsBean
     * @param filePath
     * @param extractUtils
     * @return
     */
    private String resolveVars(String endFilePath, DatasetBean dsBean, String filePath, ExtractUtils extractUtils) {
        return extractUtils.resolveVars(endFilePath, dsBean, filePath);

    }

    private void setUpSidebar(HttpServletRequest request) {
        if (sidebarInit.getAlertsBoxSetup() == SidebarEnumConstants.OPENALERTS) {
            request.setAttribute("alertsBoxSetup", true);
        }

        if (sidebarInit.getInfoBoxSetup() == SidebarEnumConstants.OPENINFO) {
            request.setAttribute("infoBoxSetup", true);
        }
        if (sidebarInit.getInstructionsBoxSetup() == SidebarEnumConstants.OPENINSTRUCTIONS) {
            request.setAttribute("instructionsBoxSetup", true);
        }

        if (!(sidebarInit.getEnableIconsBoxSetup() == SidebarEnumConstants.DISABLEICONS)) {
            request.setAttribute("enableIconsBoxSetup", true);
        }
    }

    public SidebarInit getSidebarInit() {
        return sidebarInit;
    }

    public void setSidebarInit(SidebarInit sidebarInit) {
        this.sidebarInit = sidebarInit;
    }

    private String resolveExportFilePath(String epBeanFileName) {
        // String retMe = "";
        //String epBeanFileName = epBean.getExportFileName();
        // important that this goes first, tbh
        if (epBeanFileName.contains("$datetime")) {
            String dateTimeFilePattern = "yyyy-MM-dd-HHmmssSSS";
            SimpleDateFormat sdfDir = new SimpleDateFormat(dateTimeFilePattern);
            epBeanFileName = epBeanFileName.replace("$datetime", sdfDir.format(new java.util.Date()));
        } else if (epBeanFileName.contains("$date")) {
            String dateFilePattern = "yyyy-MM-dd";
            SimpleDateFormat sdfDir = new SimpleDateFormat(dateFilePattern);
            epBeanFileName = epBeanFileName.replace("$date", sdfDir.format(new java.util.Date()));
            // sdfDir.format(new java.util.Date())
            // retMe = epBean.getFileLocation() + File.separator + epBean.getExportFileName() + "." + epBean.getPostProcessing().getFileType();
        } else {
            // retMe = epBean.getFileLocation() + File.separator + epBean.getExportFileName() + "." + epBean.getPostProcessing().getFileType();
        }
        return epBeanFileName;// + "." + epBean.getPostProcessing().getFileType();// not really the case - might be text to pdf
        // return retMe;
    }

    private boolean mayProceed(HttpServletRequest request) {

        HttpSession session = request.getSession();
        StudyUserRoleBean currentRole = (StudyUserRoleBean) session.getAttribute("userRole");

        Role r = currentRole.getRole();

        if (r.equals(Role.STUDYDIRECTOR) || r.equals(Role.COORDINATOR) || r.equals(Role.MONITOR)
                || currentRole.getRole().equals(Role.INVESTIGATOR)) {
            return true;
        }
        return false;
    }

}
