
package com.creactiviti.piper.core.task;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.springframework.util.Assert;

import com.creactiviti.piper.core.DSL;
import com.creactiviti.piper.core.context.ContextRepository;
import com.creactiviti.piper.core.context.MapContext;
import com.creactiviti.piper.core.messenger.Messenger;
import com.creactiviti.piper.core.messenger.Queues;
import com.creactiviti.piper.core.uuid.UUIDGenerator;

/**
 * A {@link TaskDispatcher} implementation which implements a parallel
 * for-each construct. The dispatcher works by executing 
 * the <code>iteratee</code> function on each item on the <code>list</code>.
 * 
 * @author Arik Cohen
 * @since Apr 25, 2017
 */
public class EachTaskDispatcher implements TaskDispatcher<TaskExecution>, TaskDispatcherResolver {

  private final TaskDispatcher taskDispatcher;
  private final TaskEvaluator taskEvaluator = new SpelTaskEvaluator();
  private final TaskExecutionRepository taskExecutionRepo;
  private final Messenger messenger;
  private final ContextRepository contextRepository;
  private final CounterRepository counterRepository;

  public EachTaskDispatcher (TaskDispatcher aTaskDispatcher, TaskExecutionRepository aTaskExecutionRepo, Messenger aMessenger, ContextRepository aContextRepository, CounterRepository aCounterRepository) {
    taskDispatcher = aTaskDispatcher;
    taskExecutionRepo = aTaskExecutionRepo;
    messenger = aMessenger;
    contextRepository = aContextRepository;
    counterRepository = aCounterRepository;
  }

  @Override
  public void dispatch (TaskExecution aTask) {
    List<Object> list = aTask.getList("list", Object.class);
    Assert.notNull(list,"'list' property can't be null");
    Map<String, Object> iteratee = aTask.getMap("iteratee");
    Assert.notNull(list,"'iteratee' property can't be null");
    
    SimpleTaskExecution parentEachTask = SimpleTaskExecution.createForUpdate(aTask);
    parentEachTask.setStartTime(new Date ());
    parentEachTask.setStatus(TaskStatus.STARTED);
    taskExecutionRepo.merge(parentEachTask);
    
    if(list.size() > 0) {
      counterRepository.set(aTask.getId(), list.size());
      for(int i=0; i<list.size(); i++) {
        Object item = list.get(i);
        SimpleTaskExecution eachTask = SimpleTaskExecution.createFromMap(iteratee);
        eachTask.setId(UUIDGenerator.generate());
        eachTask.setParentId(aTask.getId());
        eachTask.setStatus(TaskStatus.CREATED);
        eachTask.setJobId(aTask.getJobId());
        eachTask.setCreateTime(new Date());
        eachTask.setPriority(aTask.getPriority());
        eachTask.setTaskNumber(i+1);
        MapContext context = new MapContext (contextRepository.peek(aTask.getId()));
        context.set(aTask.getString("itemVar","item"), item);
        context.set(aTask.getString("itemIndex","itemIndex"), i);
        contextRepository.push(eachTask.getId(), context);
        TaskExecution evaluatedEachTask = taskEvaluator.evaluate(eachTask, context);
        taskExecutionRepo.create(evaluatedEachTask);
        taskDispatcher.dispatch(evaluatedEachTask);
      }
    }
    else {
      SimpleTaskExecution completion = SimpleTaskExecution.createForUpdate(aTask);
      completion.setEndTime(new Date());
      messenger.send(Queues.COMPLETIONS, completion);
    }
  }

  @Override
  public TaskDispatcher resolve (Task aTask) {
    if(aTask.getType().equals(DSL.EACH)) {
      return this;
    }
    return null;
  }

}
