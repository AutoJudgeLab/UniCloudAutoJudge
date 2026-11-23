import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';
const success = new Rate('submission_success');
const latency = new Trend('acceptance_latency_ms', true);
const vus = Number(__ENV.VUS || 5);
export const options = {
  scenarios: { submissions: __ENV.ITERATIONS
    ? { executor: 'shared-iterations', vus, iterations: Number(__ENV.ITERATIONS), maxDuration: '2m' }
    : { executor: 'constant-vus', vus, duration: __ENV.DURATION || '30s' } },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};
export function setup() {
  if (!__ENV.BASE_URL || !__ENV.ASSIGNMENT_ID || !__ENV.STUDENT_TOKEN) throw new Error('BASE_URL, ASSIGNMENT_ID and STUDENT_TOKEN are required');
}
export default function () {
  const key = 'load-' + Date.now() + '-' + __VU + '-' + __ITER;
  const started = Date.now();
  const response = http.post(__ENV.BASE_URL + '/api/assignments/' + __ENV.ASSIGNMENT_ID + '/submissions', {
    file: http.file('class Main {}\n', 'Main.java', 'text/plain'), programmingLanguage: 'JAVA',
  }, { headers: { Authorization: 'Bearer ' + __ENV.STUDENT_TOKEN, 'Idempotency-Key': key },
       tags: { name: 'submit' }, timeout: '30s' });
  const accepted = response.status === 201;
  success.add(accepted);
  if (accepted) latency.add(Date.now() - started);
  check(response, { 'durably accepted (201)': () => accepted });
}
export function handleSummary(data) {
  const result = JSON.stringify(data, null, 2);
  return { stdout: result + '\n', [__ENV.SUMMARY_PATH || 'load-summary.json']: result };
}

