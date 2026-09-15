import test from 'node:test';
import assert from 'node:assert/strict';
import {reviewInput,parseReview,approveSupplement,searchLink} from '../web/material-review.js';
import {templatePlan,validatePlan} from '../web/mangal-core.js';
const input={objective:'Explain how evidence supports a conclusion.',level:'Age 14',excerpt:'A conclusion needs supporting observations and a statement of its limits.'};
test('review rejects fabricated supporting quotations',()=>{
 const f={requirement:'Evidence',status:'supported',quote:'Invented evidence',reason:'Needed for the objective',remedy:'Add observations',search:'worked evidence examples'};
 assert.throws(()=>parseReview(JSON.stringify({findings:[f]}),input));
 f.quote=input.excerpt;assert.equal(parseReview(JSON.stringify({findings:[f]}),input).findings.length,1);
 assert.throws(()=>reviewInput({...input,excerpt:''}));
});
test('supplement approval preserves original pages and rejects unsafe source schemes',()=>{
 const pages=[{page:1,text:input.excerpt}],before=JSON.stringify(pages);
 assert.throws(()=>approveSupplement(pages,'Example',input.excerpt,'javascript:alert(1)'));
 const addition=approveSupplement(pages,'Example',input.excerpt,'https://example.org/lesson');
 assert.equal(addition.page,2);assert.equal(JSON.stringify(pages),before);
 assert.ok(searchLink('a & b').endsWith('a%20%26%20b'));
});
test('flexible coverage requires an explicit rationale while old plans retain their minimum',()=>{
 const plan=templatePlan(input.objective,input.excerpt,1);plan.questions=plan.questions.slice(0,8);
 assert.throws(()=>validatePlan(plan,[{page:1,text:input.excerpt}]));
 plan.selectionPolicy='objective-coverage/1';assert.throws(()=>validatePlan(plan,[{page:1,text:input.excerpt}]));
 plan.coverageReason='These eight operations cover the selected introductory objective.';
 validatePlan(plan,[{page:1,text:input.excerpt}]);
});
