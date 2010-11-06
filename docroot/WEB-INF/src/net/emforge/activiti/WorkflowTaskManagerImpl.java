package net.emforge.activiti;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.emforge.activiti.dao.ProcessInstanceExtensionDao;
import net.emforge.activiti.identity.LiferayIdentitySessionImpl;

import org.activiti.engine.HistoryService;
import org.activiti.engine.IdentityService;
import org.activiti.engine.ProcessEngine;
import org.activiti.engine.RepositoryService;
import org.activiti.engine.RuntimeService;
import org.activiti.engine.TaskService;
import org.activiti.engine.history.HistoricActivityInstance;
import org.activiti.engine.history.HistoricActivityInstanceQuery;
import org.activiti.engine.repository.ProcessDefinition;
import org.activiti.engine.repository.ProcessDefinitionQuery;
import org.activiti.engine.runtime.ProcessInstance;
import org.activiti.engine.task.IdentityLink;
import org.activiti.engine.task.Task;
import org.activiti.engine.task.TaskQuery;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.liferay.portal.kernel.dao.orm.QueryUtil;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.util.OrderByComparator;
import com.liferay.portal.kernel.workflow.DefaultWorkflowTask;
import com.liferay.portal.kernel.workflow.WorkflowException;
import com.liferay.portal.kernel.workflow.WorkflowTask;
import com.liferay.portal.kernel.workflow.WorkflowTaskAssignee;
import com.liferay.portal.kernel.workflow.WorkflowTaskManager;
import com.liferay.portal.model.User;

@Service("workflowTaskManager")
public class WorkflowTaskManagerImpl implements WorkflowTaskManager {
	private static Log _log = LogFactoryUtil.getLog(WorkflowTaskManagerImpl.class);

	@Autowired
	ProcessEngine processEngine;
	@Autowired
	RuntimeService runtimeService;
	@Autowired
	IdentityService identityService;
	@Autowired
	TaskService taskService;
	@Autowired
	HistoryService historyService;
	@Autowired 
	RepositoryService repositoryService;
	
	@Autowired
	IdMappingService idMappingService;
	@Autowired
	LiferayIdentitySessionImpl liferayIdentitySession;
	@Autowired
	ProcessInstanceExtensionDao processInstanceExtensionDao;
	
	@Override
	public WorkflowTask assignWorkflowTaskToRole(long companyId, long userId,
			long workflowTaskId, long roleId, String comment, Date dueDate,
			Map<String, Serializable> context) throws WorkflowException {
		_log.error("Method is not implemented"); // TODO
		return null;
	}

	@Transactional
	@Override
	public WorkflowTask assignWorkflowTaskToUser(long companyId, long userId,
			long workflowTaskId, long assigneeUserId, String comment,
			Date dueDate, Map<String, Serializable> context)
			throws WorkflowException {
		identityService.setAuthenticatedUserId(String.valueOf(userId));
		
		String taskId = String.valueOf(workflowTaskId);
		Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
		
		// assign task
		taskService.setAssignee(taskId, String.valueOf(userId));
		
		// update vars
		Map<String, Object> vars = WorkflowInstanceManagerImpl.convertFromContext(context);
		runtimeService.setVariables(task.getProcessInstanceId(), vars);
		
		// update dueDate
		/* TODO Looks like not supported in Acitiviti
		task.setDueDate();
		
		
		taskService.saveTask(task);
		*/
		
		addTaskComment(userId, taskId, comment);
		
		
		task = taskService.createTaskQuery().taskId(taskId).singleResult();
		return getWorkflowTask(task);
	}

	@Override
	public WorkflowTask completeWorkflowTask(long companyId, long userId, long workflowTaskId, 
											 String transitionName, String comment,
											 Map<String, Serializable> context) throws WorkflowException {
		identityService.setAuthenticatedUserId(String.valueOf(userId));
		
		String taskId = String.valueOf(workflowTaskId);
		
		// complete task
		Map<String, Object> vars = WorkflowInstanceManagerImpl.convertFromContext(context);
		taskService.complete(taskId, /* transitionName, */ vars);

		addTaskComment(userId, taskId, comment);
		
		// TODO - find the next task
		return null;
	}

	@Override
	public List<String> getNextTransitionNames(long companyId, long userId, long workflowTaskId) throws WorkflowException {
		/* TODO Outcomes looks like is not supported in Acitiviti - will need implement by own
		 
		 
		TaskService taskService = processEngine.getTaskService();
		Set<String> outcomes = taskService.getOutcomes(String.valueOf(workflowTaskId));
		_log.debug(outcomes.size() + " found for task " + workflowTaskId);
		List<String> result = new ArrayList<String>(outcomes.size());
		result.addAll(outcomes);
		
		return result;
		*/
		
		List<String> result = new ArrayList<String>(1);
		result.add("Done");
		
		return result;
	}

	@Override
	public long[] getPooledActorsIds(long companyId, long workflowTaskId) throws WorkflowException {
		String taskId = String.valueOf(workflowTaskId);
		
		List<IdentityLink> participations = taskService.getIdentityLinksForTask(taskId);
		
		Set<Long> userIds = new HashSet<Long>();
		
		for (IdentityLink participation : participations) {
			if (StringUtils.isNotEmpty(participation.getGroupId())) {
				List<org.activiti.engine.identity.User> users = liferayIdentitySession.findUsersByGroup(companyId, participation.getGroupId());
				for (org.activiti.engine.identity.User user : users) {
					userIds.add(Long.valueOf(user.getId()));
				}
			}
		}
		
		long[] result = new long[userIds.size()];
		int i = 0;
		for (Long userId : userIds) {
			result[i++] = userId;
		}
		
		return result;
	}

	@Transactional(readOnly=true)
	@Override
	public WorkflowTask getWorkflowTask(long companyId, long workflowTaskId) throws WorkflowException {
		String taskId = String.valueOf(workflowTaskId);
		Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
		
		if (task != null) {
			return getWorkflowTask(task);
		} else {
			_log.debug("Cannot find active task " + workflowTaskId + " , try to find in history");
			HistoricActivityInstance historyTask = historyService.createHistoricActivityInstanceQuery().activityId(taskId).singleResult();
			
			if (historyTask != null) {
				return getHistoryWorkflowTask(historyTask);
			} else {
				_log.info("Cannot find task " + workflowTaskId);
				return null;
			}
			
		}
		
	}

	@Override
	public int getWorkflowTaskCount(long companyId, Boolean completed)
			throws WorkflowException {
		_log.error("Method is not implemented"); // TODO
		return 0;
	}

	@Override
	public int getWorkflowTaskCountByRole(long companyId, long roleId,
			Boolean completed) throws WorkflowException {
		_log.error("Method is not implemented"); // TODO
		return 0;
	}

	@Override
	public int getWorkflowTaskCountByUser(long companyId, long userId,
			Boolean completed) throws WorkflowException {
		_log.error("Method is not implemented"); // TODO
		return 0;
	}

	@Override
	public int getWorkflowTaskCountByUserRoles(long companyId, long userId,
			Boolean completed) throws WorkflowException {
		_log.error("Method is not implemented"); // TODO
		return 0;
	}

	@Override
	public List<WorkflowTask> getWorkflowTasks(long companyId,
			Boolean completed, int start, int end,
			OrderByComparator orderByComparator) throws WorkflowException {
		_log.error("Method is not implemented"); // TODO
		return null;
	}

	@Override
	public List<WorkflowTask> getWorkflowTasksByRole(long companyId,
			long roleId, Boolean completed, int start, int end,
			OrderByComparator orderByComparator) throws WorkflowException {
		_log.error("Method is not implemented"); // TODO
		return null;
	}

	@Override
	public List<WorkflowTask> getWorkflowTasksByUser(long companyId,
			long userId, Boolean completed, int start, int end,
			OrderByComparator orderByComparator) throws WorkflowException {
		_log.error("Method is not implemented"); // TODO
		return null;
	}

	@Override
	public List<WorkflowTask> getWorkflowTasksByUserRoles(long companyId,
			long userId, Boolean completed, int start, int end,
			OrderByComparator orderByComparator) throws WorkflowException {
		_log.error("Method is not implemented"); // TODO
		return null;
	}

	@Transactional(readOnly=true)
	@Override
	public List<WorkflowTask> search(long companyId, long userId,
			String keywords, Boolean completed, Boolean searchByUserRoles,
			int start, int end, OrderByComparator orderByComparator)
			throws WorkflowException {
		// TODO first of all - implement search by companyId, userId, completed (search by UserRoles == false)
		if (userId == 0 || StringUtils.isNotEmpty(keywords)) {
			_log.warn("Method is partially implemented"); // TODO
		}
		
        if (searchByUserRoles != null && searchByUserRoles == true) {
        	if (completed == null || !completed) {
        		TaskQuery taskQuery = taskService.createTaskQuery().taskCandidateUser(String.valueOf(userId));
        		
        		/* TODO Ordering is not implemented yet
        		if (orderByComparator != null) {
	    			if (orderByComparator.getOrderByFields().length > 1) {
	    				_log.warn("Method is partially implemented");
	    			} else {
	    				if (orderByComparator.isAscending()) {
	    					taskQuery.orderAsc(orderByComparator.getOrderByFields()[0]);
	    				} else {
	    					taskQuery.orderDesc(orderByComparator.getOrderByFields()[0]);
	    				}
	    			}
	    		}
	            */
        		if ((start != QueryUtil.ALL_POS) && (end != QueryUtil.ALL_POS)) {
        			taskQuery.listPage(start, end - start);
        		}
        		
	            List<Task> list = taskQuery.list();
	            
	        	return getWorkflowTasks(list);
        	} else {
        		_log.warn("Method is partially implemented"); // TODO
        		return new ArrayList<WorkflowTask>();
        	}
        } else {
        	if (completed == null || !completed) {
	        	TaskQuery taskQuery = taskService.createTaskQuery();
	        	taskQuery = taskQuery.taskAssignee(String.valueOf(userId));
	        	
	        	/** TODO Ordering is not supported yet
	            if (orderByComparator != null) {
	    			if (orderByComparator.getOrderByFields().length > 1) {
	    				_log.warn("Method is partially implemented");
	    			} else {
	    				if (orderByComparator.isAscending()) {
	    					taskQuery.orderAsc(orderByComparator.getOrderByFields()[0]);
	    				} else {
	    					taskQuery.orderDesc(orderByComparator.getOrderByFields()[0]);
	    				}
	    			}
	    		}
	    		*/
	        	
        		if ((start != QueryUtil.ALL_POS) && (end != QueryUtil.ALL_POS)) {
        			taskQuery.listPage(start, end - start);
        		}
	            
	            List<Task> list = taskQuery.list();
	            
	    		return getWorkflowTasks(list);
        	} else {
        		// search for completed tasks in history service
        		// TODO filter to return only completed tasks
        		HistoricActivityInstanceQuery query = historyService.createHistoricActivityInstanceQuery().taskAssignee(String.valueOf(userId));

        		/* TODO ordering is not supported yet
        		if (orderByComparator != null) {
	    			if (orderByComparator.getOrderByFields().length > 1) {
	    				_log.warn("Method is partially implemented");
	    			} else {
	    				if (orderByComparator.isAscending()) {
	    					query.orderAsc(orderByComparator.getOrderByFields()[0]);
	    				} else {
	    					query.orderDesc(orderByComparator.getOrderByFields()[0]);
	    				}
	    			}
	    		}
	    		*/
        		
        		if ((start != QueryUtil.ALL_POS) && (end != QueryUtil.ALL_POS)) {
        			query.listPage(start, end - start);
        		}
        		
        		List<HistoricActivityInstance> list = query.list();
        		
        		return getHistoryWorkflowTasks(list);
        	}
        }
	}

	@Override
	public int searchCount(long companyId, long userId, String keywords,
			Boolean completed, Boolean searchByUserRoles)
			throws WorkflowException {
		// TODO first of all - implement search by companyId, userId, completed (search by UserRoles == false)
		if (userId == 0 || StringUtils.isNotEmpty(keywords)) {
			_log.warn("Method is partially implemented"); // TODO
		}
		if (searchByUserRoles != null && searchByUserRoles == true) {
        	if (completed == null || !completed) {
        		TaskQuery taskQuery = taskService.createTaskQuery().taskCandidateUser(String.valueOf(userId));
	        	
        		Long count = taskQuery.count();
	    		return count.intValue();
        	} else {
        		_log.warn("Method is partially implemented"); // TODO
        		return 0;
        	}
        } else {
        	if (completed == null || !completed) {
	        	TaskQuery taskQuery = taskService.createTaskQuery();
	        	taskQuery = taskQuery.taskAssignee(String.valueOf(userId));
	        	
	    		Long count = taskQuery.count();
	    		return count.intValue();
        	} else {
        		// TODO filter to return only completed tasks
        		HistoricActivityInstanceQuery query = historyService.createHistoricActivityInstanceQuery().taskAssignee(String.valueOf(userId));
        		
        		Long count = query.count();
	    		return count.intValue();
        	}
        }
	}

	@Override
	public WorkflowTask updateDueDate(long companyId, long userId, long workflowTaskId, String comment, Date dueDate)
			throws WorkflowException {
		identityService.setAuthenticatedUserId(String.valueOf(userId));
		
		TaskService taskService = processEngine.getTaskService();
		String taskId = String.valueOf(workflowTaskId);
		
		// update dueDate
		/* TODO DueDate looks like is not supported by Activiti
		Task task = taskService.getTask(taskId);
		task.setDuedate(dueDate);
		taskService.saveTask(task);
		
		addTaskComment(userId, taskId, comment);
		*/
		
		Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
		return getWorkflowTask(task);
	}

	@Override
	public int getWorkflowTaskCountBySubmittingUser(long companyId,
			long userId, Boolean completed) throws WorkflowException {
		_log.error("Method is not implemented"); // TODO
		return 0;
	}

	@Override
	public List<WorkflowTask> getWorkflowTasksBySubmittingUser(long companyId,
			long userId, Boolean completed, int start, int end,
			OrderByComparator orderByComparator) throws WorkflowException {
		_log.error("Method is not implemented"); // TODO
		return null;
	}

	@Override
	public List<WorkflowTask> search(long companyId, long userId,
			String taskName, String assetType, Date dueDateGT, Date dueDateLT,
			Boolean completed, Boolean searchByUserRoles, boolean andOperator,
			int start, int end, OrderByComparator orderByComparator)
			throws WorkflowException {
		_log.error("Method is not implemented"); // TODO
		return null;
	}

	@Override
	public int searchCount(long companyId, long userId, String taskName,
			String assetType, Date dueDateGT, Date dueDateLT,
			Boolean completed, Boolean searchByUserRoles, boolean andOperator)
			throws WorkflowException {
		_log.error("Method is not implemented"); // TODO
		return 0;
	}

	@Override
	public int getWorkflowTaskCountByWorkflowInstance(long companyId, Long userId, long workflowInstanceId, Boolean completed) throws WorkflowException {
		if (completed) {
			_log.warn("Method is partially implemented"); // TODO
			return 0;
		} else {
			TaskService taskService = processEngine.getTaskService();
			TaskQuery taskQuery = taskService.createTaskQuery();
			Long count = taskQuery.taskAssignee(String.valueOf(userId)).processInstanceId(idMappingService.getJbpmProcessInstanceId(workflowInstanceId)).count();
			
			return count.intValue();
		}
	}

	@Override
	public List<WorkflowTask> getWorkflowTasksByWorkflowInstance(long companyId, Long userId, long workflowInstanceId,
																 Boolean completed, int start, int end,
																 OrderByComparator orderByComparator) throws WorkflowException {
		if (completed) {
			_log.warn("Method is partially implemented"); // TODO
			return null;
		} else {
			TaskService taskService = processEngine.getTaskService();
			TaskQuery taskQuery = taskService.createTaskQuery();
			taskQuery.taskAssignee(String.valueOf(userId)).processInstanceId(idMappingService.getJbpmProcessInstanceId(workflowInstanceId));
			
			/* TODO Ordering is not supported
			if (orderByComparator != null) {
    			if (orderByComparator.getOrderByFields().length > 1) {
    				_log.warn("Method is partially implemented");
    			} else {
    				if (orderByComparator.isAscending()) {
    					taskQuery.orderAsc(orderByComparator.getOrderByFields()[0]);
    				} else {
    					taskQuery.orderDesc(orderByComparator.getOrderByFields()[0]);
    				}
    			}
    		}
    		*/
			
    		if ((start != QueryUtil.ALL_POS) && (end != QueryUtil.ALL_POS)) {
    			taskQuery.listPage(start, end - start);
    		}
            
            List<Task> list = taskQuery.list();
            
    		return getWorkflowTasks(list);
		}
	}

	/** Convert jBPM Tasks to Liferay WorkflowTask
	 * 
	 * @param list
	 * @return
	 */
	private List<WorkflowTask> getWorkflowTasks(List<Task> list) {
		List<WorkflowTask> result = new ArrayList<WorkflowTask>(list.size());
		
		for (Task task : list) {
			result.add(getWorkflowTask(task));
		}
		
		return result;
	}

	/** Convert jBPM Task to Liferay WorkflowTask
	 * 
	 * @param task
	 * @return
	 */
	private WorkflowTask getWorkflowTask(Task task) {
		DefaultWorkflowTask workflowTask = new DefaultWorkflowTask();
		
		// TODO replace getExecutionId with getProcessInstanceId then it will work
		String processInstanceId = task.getExecutionId();
		ProcessInstance processInstance = runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
		
		ProcessDefinitionQuery processDefinitionQuery = repositoryService.createProcessDefinitionQuery();
        processDefinitionQuery.processDefinitionId(processInstance.getProcessDefinitionId());
		ProcessDefinition processDef =  processDefinitionQuery.singleResult();
		
		// TODO setAsynchronous(!task.isBlocking());
		// TODO setCompletionDate(taskInstance.getEnd());
		workflowTask.setCreateDate(task.getCreateTime());
		workflowTask.setDescription(task.getDescription());
		// TODO workflowTask.setDueDate(task.getDuedate());
		workflowTask.setName(task.getName());
		
		Map<String, Object> vars = runtimeService.getVariables(processInstanceId);
		workflowTask.setOptionalAttributes(WorkflowInstanceManagerImpl.convertFromVars(vars));
		
		
		workflowTask.setWorkflowDefinitionId(idMappingService.getLiferayWorkflowDefinitionId(processDef.getId()));
		
		workflowTask.setWorkflowDefinitionName(processDef.getName());
		workflowTask.setWorkflowDefinitionVersion(processDef.getVersion());
		workflowTask.setWorkflowInstanceId(idMappingService.getLiferayProcessInstanceId(processInstanceId));

		/*
		long companyId = GetterUtil.getLong((String)taskService.getVariable(task.getId(), "companyId"));
		long groupId = GetterUtil.getLong((String)taskService.getVariable(task.getId(), "groupId"));
		*/
		
		String assignee = task.getAssignee();
		if (assignee != null && !"null".equals(assignee)) { // TODO check why we have this "null" string
			List<WorkflowTaskAssignee> workflowTaskAssignees = new ArrayList<WorkflowTaskAssignee>(1);
			WorkflowTaskAssignee workflowTaskAssignee = new WorkflowTaskAssignee(
					User.class.getName(), Long.valueOf(assignee));
			workflowTaskAssignees.add(workflowTaskAssignee);
			
			workflowTask.setWorkflowTaskAssignees(workflowTaskAssignees);
		}
		
		workflowTask.setWorkflowTaskId(Long.valueOf(task.getId()));
		
		return workflowTask;
	}

	private List<WorkflowTask> getHistoryWorkflowTasks(List<HistoricActivityInstance> list) {
		List<WorkflowTask> result = new ArrayList<WorkflowTask>(list.size());
		
		for (HistoricActivityInstance task : list) {
			result.add(getHistoryWorkflowTask(task));
		}
		
		return result;
	}

	private WorkflowTask getHistoryWorkflowTask(HistoricActivityInstance task) {
		DefaultWorkflowTask workflowTask = new DefaultWorkflowTask();
		
		// TODO setAsynchronous(!task.isBlocking());
		workflowTask.setCreateDate(task.getStartTime());
		workflowTask.setCompletionDate(task.getEndTime());
		
		// TODO workflowTask.setDescription(task.getDescription());
		// TODO workflowTask.setDueDate(task.getDuedate());
		
		workflowTask.setName(task.getActivityName());
					
		// get process def from activity
		String processDefId = task.getProcessDefinitionId();
		ProcessDefinitionQuery processDefinitionQuery = repositoryService.createProcessDefinitionQuery();
        processDefinitionQuery.processDefinitionId(processDefId);
		ProcessDefinition processDef =  processDefinitionQuery.singleResult();
		workflowTask.setWorkflowDefinitionId(idMappingService.getLiferayWorkflowDefinitionId(processDefId));
		workflowTask.setWorkflowDefinitionName(processDef.getName());
		workflowTask.setWorkflowDefinitionVersion(processDef.getVersion());
		
		/* Not applicable for history task
		Set<String> variableNames = taskService.getVariableNames(task.getId());
		Map<String, Object> vars = taskService.getVariables(task.getId(), variableNames);
		workflowTask.setOptionalAttributes(WorkflowInstanceManagerImpl.convertFromVars(vars));
		*/
		workflowTask.setWorkflowInstanceId(idMappingService.getLiferayProcessInstanceId(task.getExecutionId()));

		/*
		long companyId = GetterUtil.getLong((String)taskService.getVariable(task.getId(), "companyId"));
		long groupId = GetterUtil.getLong((String)taskService.getVariable(task.getId(), "groupId"));
		*/
		
		String assignee = task.getAssignee();
		if (assignee != null && !"null".equals(assignee)) { // TODO check why we have this "null" string
			List<WorkflowTaskAssignee> workflowTaskAssignees = new ArrayList<WorkflowTaskAssignee>(1);
			WorkflowTaskAssignee workflowTaskAssignee = new WorkflowTaskAssignee(
					User.class.getName(), Long.valueOf(assignee));
			workflowTaskAssignees.add(workflowTaskAssignee);
			
			workflowTask.setWorkflowTaskAssignees(workflowTaskAssignees);
		}
		
		workflowTask.setWorkflowTaskId(Long.valueOf(task.getActivityId()));
		
		
		return workflowTask;
	}

	/** Add comment for the task and whole process
	 * 
	 * @param taskId
	 * @param comment
	 */
	private void addTaskComment(Long userId, String taskId, String comment) {
		// TODO Looks like comments is not supported at all in Activiti :(
		return;
	}

}