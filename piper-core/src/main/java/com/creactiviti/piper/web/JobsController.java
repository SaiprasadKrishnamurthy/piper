
package com.creactiviti.piper.web;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.creactiviti.piper.core.Coordinator;
import com.creactiviti.piper.core.Page;
import com.creactiviti.piper.core.annotations.ConditionalOnCoordinator;
import com.creactiviti.piper.core.job.Job;
import com.creactiviti.piper.core.job.JobRepository;

@RestController
@ConditionalOnCoordinator
public class JobsController {

  @Autowired private JobRepository jobRepository;
  @Autowired private Coordinator coordinator;
  
  @GetMapping(value="/jobs")
  public Page<Job> list (@RequestParam(value="p",defaultValue="1") Integer aPageNumber) {
    return jobRepository.findAll(aPageNumber);
  }
  
  @PostMapping("/jobs")
  public Job create (@RequestBody Map<String, Object> aJobRequest) {
    return coordinator.create(aJobRequest);
  }
  
  @GetMapping(value="/jobs/{id}")
  public Job get (@PathVariable("id")String aJobId) {
    Job job = jobRepository.findOne (aJobId);
    Assert.notNull(job,"Unknown job: " + aJobId);
    return job;
  }
  
  @PutMapping(value="/jobs/{id}/restart")
  public Job restart (@PathVariable("id")String aJobId) {
    return coordinator.resume(aJobId);
  }
  
  @PutMapping(value="/jobs/{id}/stop")
  public Job step (@PathVariable("id")String aJobId) {
    return coordinator.stop(aJobId);
  }
    
}
