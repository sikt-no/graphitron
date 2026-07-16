/* Shared script for roadmap concept explainer pages (R486): quiz interaction.
   A .quiz block holds .choice buttons (data-correct="true" on the right one)
   and one .explain block revealed after the first answer. */
function choose(btn){
  var quiz = btn.closest('.quiz');
  if(quiz.classList.contains('answered')) return;
  quiz.classList.add('answered');
  var correct = btn.dataset.correct === 'true';
  quiz.querySelectorAll('.choice').forEach(function(c){
    if(c.dataset.correct === 'true') c.classList.add('right');
    else c.classList.add('dim');
  });
  if(!correct) btn.classList.add('wrong');
  quiz.querySelector('.explain').classList.add('show');
}
