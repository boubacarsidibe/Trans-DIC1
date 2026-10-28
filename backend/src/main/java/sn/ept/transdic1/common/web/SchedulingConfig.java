package sn.ept.transdic1.common.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Active les tâches planifiées (@Scheduled), par exemple la préparation des partitions. */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
