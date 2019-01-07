package org.sagebionetworks.repo.web.controller;

import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.asynch.AsyncJobId;
import org.sagebionetworks.repo.model.asynch.AsynchronousJobStatus;
import org.sagebionetworks.repo.model.costreport.DownloadCostReportRequest;
import org.sagebionetworks.repo.model.costreport.DownloadCostReportResponse;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.repo.web.UrlHelpers;
import org.sagebionetworks.repo.web.rest.doc.ControllerInfo;
import org.sagebionetworks.repo.web.service.ServiceProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Provides REST APIs for generating Cost Reports. These may only be used by the Cost Report Team.
 */
@ControllerInfo(displayName="Cost Report Services", path="repo/v1")
@Controller
@RequestMapping(UrlHelpers.REPO_PATH)
public class CostReportController extends BaseController {

	@Autowired
	private ServiceProvider serviceProvider;

	/**
	 * Asynchronously creates a report detailing the usage of Synapse storage with project-level resolution.
	 * Retrieve the results with
	 * <a href="${GET.costReport.async.get.asyncToken}">GET /costReport/async/get/{asyncToken}</a>.
	 * @param userId The ID of the user making the call. This call can only be made by users in the Cost Report team.
	 * @param request A request containing a the type of cost report to generate
	 * @return The asynchronous job ID
	 * @throws NotFoundException
	 * @throws UnauthorizedException
	 */
	@RequestMapping(value = {UrlHelpers.COST_REPORT_ASYNC_START}, method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.ACCEPTED)
	public @ResponseBody
	AsyncJobId
	generateCostReportCsv(@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
					  @RequestBody DownloadCostReportRequest request) throws NotFoundException, UnauthorizedException {
		AsynchronousJobStatus job = serviceProvider
				.getAsynchronousJobServices().startJob(userId, request);
		AsyncJobId asyncJobId = new AsyncJobId();
		asyncJobId.setToken(job.getJobId());
		return asyncJobId;
	}

	/**
	 * Get the results of a call to <a href="${POST.costReport.async.start}">POST /costReport/async/start</a>
	 * @param userId The ID of the user making the call
	 * @param asyncToken The async job token from the create/update call
	 * @return The results of the call, including a pre-signed URL to download the report.
	 * @throws Throwable
	 */
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = UrlHelpers.COST_REPORT_ASYNC_GET, method = RequestMethod.GET)
	public @ResponseBody
	DownloadCostReportResponse getCostReportResults(
			@RequestParam(value = AuthorizationConstants.USER_ID_PARAM) Long userId,
			@PathVariable String asyncToken) throws Throwable {
		AsynchronousJobStatus jobStatus = serviceProvider
				.getAsynchronousJobServices().getJobStatusAndThrow(userId,
						asyncToken);
		return (DownloadCostReportResponse) jobStatus.getResponseBody();
	}
}
